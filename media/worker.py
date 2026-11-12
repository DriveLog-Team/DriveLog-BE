"""Local DriveLog media worker. Inputs are trusted job manifests from the ingest stage."""
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import wave
import gemini_demo
import road_privacy
import sound_events
from file_hash import digest
from extraction_policy import require_extraction, reviewed_intervals, overlapping
from semantic_ranker import MemoryRanker, REVISION


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def invoke(args, cwd=None):
    result = subprocess.run([str(a) for a in args], cwd=cwd, capture_output=True, timeout=3600)
    if result.returncode:
        raise ValueError(f"{Path(args[0]).name} failed ({result.returncode}): " +
                         result.stderr.decode("utf-8", "replace")[-800:])
    return result.stderr.decode("utf-8", "replace")


def ff(*args, cwd=None):
    return invoke([os.environ.get("FFMPEG", "ffmpeg"), "-hide_banner", "-nostdin", "-y", *args], cwd)


def input_args(path):
    return ["-protocol_whitelist", "file,pipe", "-i", path]


def media_file(root, name):
    path = (root / name).resolve(strict=True)
    if not path.is_relative_to(root) or not path.is_file():
        raise ValueError("Expected a media file within the trip directory")
    return path


def require_audio_consent(manifest_path):
    require_extraction(read_json(manifest_path))


def intervals(duration, silence_log):
    """Cut long silences and preserve a little room around complete turns."""
    spans = []
    cursor = 0
    for kind, seconds in re.findall(r"silence_(start|end):\s*([\d.]+)", silence_log):
        at = min(duration, round(float(seconds) * 1000))
        if kind == "start" and at - cursor >= 500:
            spans.append((cursor, min(duration, at + 200)))
        cursor = max(0, at - 150) if kind == "end" else duration
    if duration - cursor >= 500:
        spans.append((cursor, duration))
    return spans


def transcribe(audio, wd, source_offset, manifest_path, blocked=()):
    with wave.open(str(audio), "rb") as wav:
        duration = round(1000 * wav.getnframes() / wav.getframerate())
    silence = ff(*input_args(audio), "-af", "silencedetect=noise=-38dB:d=2.0", "-f", "null", "-")
    cues = []
    exe = os.environ.get("WHISPER_CLI", "whisper-cli")
    model = os.environ["WHISPER_MODEL"]
    safe = [(a-source_offset,b-source_offset) for s,e in intervals(duration,silence)
            for a,b in road_privacy.safe_parts(source_offset+s,source_offset+e,blocked) if b-a >= 500]
    windows=((start,min(start+30000,end)) for begin,end in safe for start in range(begin,end,30000))
    for group, (start, end) in enumerate(windows):
        require_audio_consent(manifest_path)
        chunk = wd / "speech.wav"
        ff("-ss", start / 1000, *input_args(audio), "-t", (end-start)/1000, "-c:a", "pcm_s16le", chunk)
        prefix = wd / "whisper"
        model_path = Path(model).resolve()
        model_arg = os.path.relpath(model_path, wd) if model_path.anchor == wd.anchor else str(model_path)
        invoke([exe, "-m", model_arg, "-l", "ko", "-f", chunk.name, "-oj", "-of", prefix.name,
                "-t", "2", "-ml", "48", "-sow", "-ng"], cwd=wd)
        data = read_json(prefix.with_suffix(".json"))
        for segment in data["transcription"]:
            a = max(0, int(segment["offsets"]["from"]))
            b = min(end - start, int(segment["offsets"]["to"]))
            words = segment["text"].strip()
            if words and b > a:
                cues.append(dict(group=group, startMs=source_offset+start+a,
                                 endMs=source_offset+start+b, text=words))
    return cues


def make_cards(cues, limit):
    """Treat ASR segments as indivisible. Prefer a coherent run within one speech group."""
    cards = []
    for cue in cues:
        start, end = cue["startMs"], cue["endMs"]
        if end-start > limit:
            continue
        if (not cards or cards[-1]["group"] != cue["group"] or
                end-cards[-1]["startMs"] > limit or start-cards[-1]["endMs"] > 2200):
            cards.append(dict(group=cue["group"], startMs=start, endMs=end, cues=[]))
        card = cards[-1]
        card["endMs"] = max(card["endMs"], end)
        card["cues"].append(dict(startMs=start, endMs=end, text=cue["text"]))
    for i, card in enumerate(cards):
        card["id"] = f"moment-{i+1:03}"
        lines = [c["text"] for c in card["cues"]]
        card["title"] = lines[0][:48]
        card["summary"] = " ".join(lines[:2])[:160]
    return cards


def ass_stamp(ms):
    cs = max(0, ms) // 10
    return f"{cs//360000}:{cs//6000%60:02}:{cs//100%60:02}.{cs%100:02}"


def add_surrounding_footage(card, cues, start_bound, end_bound, blocked, target_ms):
    start, end = card["startMs"], card["endMs"]
    barriers = list(blocked) + [(cue["startMs"], cue["endMs"]) for cue in cues
                                if cue["endMs"] <= start or cue["startMs"] >= end]
    left = max([start_bound] + [b for _, b in barriers if b <= start])
    right = min([end_bound] + [a for a, _ in barriers if a >= end])
    duration = min(target_ms, right-left)
    if duration > end-start:
        card["startMs"] = max(left, min(start - (duration-(end-start))//2, right-duration))
        card["endMs"] = card["startMs"] + duration


def plain(text):
    # Prevent ASS override tags or forced breaks from user-supplied caption text.
    return " ".join(text.split()).replace("\\", "＼").replace("{", "｛").replace("}", "｝")


def attach_context(cards, context):
    if not context:
        return
    for card in cards:
        events=[e for e in context.get("events",[]) if card["startMs"]<=e["atMs"]<card["endMs"]]
        card["driveContext"]=dict(departure=context.get("departure"),destination=context.get("destination"),events=events)


def subtitles(card, path):
    style = """[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
WrapStyle: 0
[V4+ Styles]
Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding
Style: Caption,Noto Sans CJK KR,54,&H00FFFFFF,&H00FFFFFF,&H00202020,&HA0000000,0,0,0,0,100,100,0,0,1,3,1,2,55,55,96,1
Style: Title,Noto Sans CJK KR,58,&H00FFFFFF,&H00FFFFFF,&H00202020,&HA0000000,-1,0,0,0,100,100,0,0,1,3,1,8,55,55,68,1
Style: Summary,Noto Sans CJK KR,37,&H00FFFFFF,&H00FFFFFF,&H00202020,&HA0000000,0,0,0,0,100,100,0,0,1,2,1,8,60,60,210,1
Style: Route,Noto Sans CJK KR,32,&H00FFFFFF,&H00FFFFFF,&H00202020,&HA0000000,0,0,0,0,100,100,0,0,1,2,1,2,48,48,30,1
Style: Place,Noto Sans CJK KR,32,&H00FFFFFF,&H00FFFFFF,&H00202020,&HA0000000,0,0,0,0,100,100,0,0,1,2,1,8,48,48,285,1
[Events]
Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
"""
    start, end = card["startMs"], card["endMs"]
    events = [(0, end-start, "Title", card["title"])]
    if card.get("recommendation", {}).get("provider") == "gemini":
        events.append((0, min(4200, end-start), "Summary", card.get("summary") or card["recommendation"]["reason"]))
    route=card.get("driveContext",{})
    places=" → ".join(p for p in (route.get("departure"),route.get("destination")) if p)
    if places:
        events.append((0,end-start,"Route",places))
    for event in route.get("events",[]):
        if event.get("label"):
            at=max(0,event["atMs"]-start)
            events.append((at,min(at+2200,end-start),"Place",event["label"]))
    for cue in card["cues"]:
        a, b = max(start, cue["startMs"]), min(end, cue["endMs"])
        if a < b:
            events.append((a-start, b-start, "Caption", cue["text"]))
    path.write_text(style + "".join(f"Dialogue: 0,{ass_stamp(a)},{ass_stamp(b)},{s},,0,0,0,,{plain(t)}\n"
                                    for a,b,s,t in events), encoding="utf-8-sig")


def render(card, tracks, audio_name, wd, dest, blur_intervals=(), masks=(), captions=True):
    start, end = card["startMs"], card["endMs"]
    duration = (end-start)/1000
    args, filters = [], []
    for i, direction in enumerate(("front","rear")):
        track = tracks[direction]
        relative = (start-track["offsetMs"])/1000
        delay = min(duration, max(0, -relative))
        # Seek separately for each recorder. A missing interval remains black.
        args += ["-ss", max(0,relative), *input_args(track["path"])]
        filters.append(f"color=c=black:s=1080x960:r=25:d={duration}[bg{i}];"
                       f"[{i}:v:0]scale=1080:960:force_original_aspect_ratio=decrease,"
                       f"setsar=1,setpts=PTS-STARTPTS+{delay}/TB[v{i}];"
                       f"[bg{i}][v{i}]overlay=(W-w)/2:(H-h)/2:eof_action=pass:repeatlast=0[p{i}]")
    audio_track = tracks[audio_name]
    args += ["-ss", (start-audio_track["offsetMs"])/1000, *input_args(audio_track["path"])]
    subtitles(card, wd / "captions.ass")
    effects = ["vstack"]
    for a, b in overlapping(card["startMs"], card["endMs"], blur_intervals):
        begin = (a - card["startMs"]) / 1000
        end = (b - card["startMs"]) / 1000
        effects.append(f"gblur=sigma=36:enable='between(t,{begin},{end})'")
    filters.append("[p0][p1]" + ",".join(effects) + "[road]")
    if road_privacy.write_mask(wd/"alpha.gray", card, masks):
        args += ["-f","rawvideo","-pixel_format","gray","-video_size","216x384","-framerate","25","-i",wd/"alpha.gray"]
        filters.extend(["[road]split[clean][copy]", "[copy]gblur=sigma=48[blurred]",
                        "[3:v]scale=1080:1920:flags=neighbor,format=gray[alpha]",
                        "[blurred][alpha]alphamerge[protected]", "[clean][protected]overlay[composite]"])
        video = "[composite]"
    else:
        video = "[road]"
    filters.append(video+("ass=captions.ass" if captions else "null")+"[video]")
    ff("-loglevel","error",*args,"-filter_complex_threads","1","-filter_complex",";".join(filters),
       "-map","[video]","-map","2:a:0","-t",duration,"-map_metadata","-1","-c:v","libx264",
       "-preset","veryfast","-threads","2","-pix_fmt","yuv420p","-c:a","aac","-movflags","+faststart",dest,cwd=wd)
    ff("-v","error","-xerror",*input_args(dest),"-map","0:v:0","-map","0:a:0","-f","null","-")


def apply_edit(cards, edit, limit):
    card = next((c for c in cards if c["id"] == edit["id"]), None)
    if card is None:
        raise ValueError("Unknown moment")
    start = edit.get("startMs",card["startMs"])
    end = edit.get("endMs",card["endMs"])
    if not (type(start) is int and type(end) is int and card["startMs"] <= start < end <= card["endMs"]
            and end-start <= limit):
        raise ValueError("Trim is outside the selected moment")
    title = edit.get("title",card["title"])
    if not isinstance(title,str) or not title.strip() or len(title)>80:
        raise ValueError("Invalid title")
    texts = edit.get("captions", [c["text"] for c in card["cues"]])
    if not isinstance(texts,list) or len(texts)!=len(card["cues"]) or any(not isinstance(t,str) or len(t)>200 for t in texts):
        raise ValueError("Invalid caption edits")
    previous_cues=card["cues"]
    card.update(startMs=start,endMs=end,title=title, cues=[dict(c,text=t,startMs=max(start,c["startMs"]),endMs=min(end,c["endMs"]))
        for c,t in zip(card["cues"],texts) if c["endMs"]>start and c["startMs"]<end])
    if previous_cues!=card["cues"]:
        card["summary"] = " ".join(c["text"] for c in card["cues"][:2])[:160] or card["title"]
    if "driveContext" in card:
        card["driveContext"]["events"]=[e for e in card["driveContext"]["events"] if start<=e["atMs"]<end]
    if "soundEvent" in card:
        event=card["soundEvent"]
        if not overlapping(start,end,[(event["startMs"],event["endMs"])]):
            card.pop("soundEvent")
        else:
            event.update(startMs=max(start,event["startMs"]),endMs=min(end,event["endMs"]))
    if "recommendation" in card:
        reason = card["recommendation"]
        evidence = next((c for c in card["cues"] if c["startMs"] < reason["quoteEndMs"]
                         and c["endMs"] > reason["quoteStartMs"]), None)
        if evidence:
            reason.update(quote=evidence["text"], quoteStartMs=evidence["startMs"], quoteEndMs=evidence["endMs"])
        else:
            card.pop("recommendation")


def caption_master(master, offset, card, wd, output):
    subtitles(card, wd / "captions.ass")
    ff("-v","error","-ss",(card["startMs"]-offset)/1000,*input_args(master),
       "-t",(card["endMs"]-card["startMs"])/1000,"-vf","ass=captions.ass",
       "-map","0:v:0","-map","0:a:0","-map_metadata","-1","-c:v","libx264","-preset","veryfast",
       "-threads","2","-c:a","aac","-movflags","+faststart",output,cwd=wd)


def from_draft(manifest, library, spec, profile, edit_file):
    canonical = library / spec["access"]["draftVersion"]
    initial = read_json(canonical/"cards.json")
    limit = {"STORY_15":15000,"SHORT_30":30000}[profile]
    edit = read_json(edit_file) if edit_file else None
    if edit is None and initial["profile"] == profile:
        return canonical
    base = edit["baseVersion"] if edit else initial["version"]
    if not re.fullmatch("[a-f0-9]{32}",base):
        raise ValueError("Invalid draft version")
    parent = read_json(library/base/"cards.json")
    if parent["sourceRevision"] != initial["sourceRevision"]:
        raise ValueError("Different protected draft")
    if edit:
        if parent["profile"] != profile:
            raise ValueError("Different edit profile")
        apply_edit(parent["cards"],edit,limit)
        parent["cards"] = [c for c in parent["cards"] if c["id"]==edit["id"]]
    else:
        for card in parent["cards"]:
            if card["endMs"]-card["startMs"]>limit:
                anchor=card.get("recommendation",{}).get("quoteStartMs",card["startMs"])
                start=min(max(card["startMs"],anchor),card["endMs"]-limit)
                apply_edit([card],dict(id=card["id"],startMs=start,endMs=start+limit),limit)
    version=hashlib.sha256(json.dumps([initial["version"],profile,edit],sort_keys=True).encode()).hexdigest()[:32]
    target=library/version
    if (target/"cards.json").is_file():
        return target
    offsets={c["id"]:c["startMs"] for c in initial["cards"]}
    with tempfile.TemporaryDirectory(dir=library) as tmp:
        wd=Path(tmp)
        stage=wd/"release";stage.mkdir()
        for card in parent["cards"]:
            require_audio_consent(manifest)
            caption_master(canonical/("master-"+card["file"]),offsets[card["id"]],card,wd,stage/card["file"])
        parent.update(version=version,profile=profile)
        write_json(stage/"cards.json",parent)
        require_audio_consent(manifest)
        stage.rename(target)
    return target


def run(manifest, library, profile, edit_file=None):
    manifest = manifest.resolve(strict=True)
    spec = read_json(manifest)
    require_audio_consent(manifest)
    road_review = reviewed_intervals(spec)
    if spec.get("access",{}).get("draftVersion"):
        return from_draft(manifest,library.resolve(),spec,profile,edit_file)
    root = manifest.parent
    tracks = {}
    for direction in ("front","rear"):
        track = spec[direction]
        if type(track["offsetMs"]) is not int or track["offsetMs"] < 0:
            raise ValueError("Expected non-negative trip-relative offsetMs")
        tracks[direction] = dict(path=media_file(root,track["file"]),offsetMs=track["offsetMs"])
    audio_name = spec["audioSource"]
    primary = tracks[audio_name]
    limit = {"STORY_15":15000,"SHORT_30":30000}[profile]
    library.mkdir(parents=True,exist_ok=True)
    library = library.resolve()
    edit = read_json(edit_file) if edit_file else None
    # Source generation and ASR identity define a reusable private transcript.
    hashes = {name:digest(track["path"]) for name,track in tracks.items()}
    source_key = [road_privacy.POLICY, spec["tripId"],audio_name, {k:t["offsetMs"] for k,t in tracks.items()},hashes,road_review,
                  digest(Path(os.environ["WHISPER_MODEL"])), "whisper-ko-silence-v2"]
    key = hashlib.sha256(json.dumps(source_key).encode()).hexdigest()
    cache = library / ("transcript-"+key+".json")
    mode = spec.get("recommendationMode", "local")
    if mode not in ("local", "gleo-demo"):
        raise ValueError("Unsupported recommendationMode")
    def authorize_gemini():
        now = read_json(manifest)
        require_extraction(now)
        if (now.get("geminiTranscriptConsent") is not True
                or now.get("recommendationMode") != "gleo-demo" or now.get("roadReview") != road_review):
            raise ValueError("Gemini transcript processing is disabled for this trip")
    if mode == "gleo-demo" and not edit:
        authorize_gemini()
    media_spec = {k: v for k, v in spec.items() if k not in ("gleoExcerptConsent", "geminiTranscriptConsent", "recommendationMode")}
    source_revision = hashlib.sha256(json.dumps([key,media_spec,hashes],
                                                sort_keys=True).encode()).hexdigest()
    fingerprint = [source_revision,profile,edit,"render-audio-events-v1",gemini_demo.REVISION if mode == "gleo-demo" else REVISION]
    version = hashlib.sha256(json.dumps(fingerprint,sort_keys=True).encode()).hexdigest()[:32]
    target = library/version
    if (target/"cards.json").is_file():
        return target
    with tempfile.TemporaryDirectory(dir=library) as temp, closing(road_privacy.MaskStore(Path(temp)/"masks.sqlite")) as mask_store:
        wd = Path(temp)
        masks, blocked, privacy = road_privacy.scan(tracks, road_review, lambda: require_audio_consent(manifest),mask_store)
        cues = []
        if edit is not None:
            base = edit.get("baseVersion","")
            if not re.fullmatch("[a-f0-9]{32}", base):
                raise ValueError("Expected a base export version")
            parent = read_json(library/base/"cards.json")
            if parent["sourceRevision"] != source_revision or parent["profile"] != profile:
                raise ValueError("Source or profile changed since the base export")
            cards = parent["cards"]
            apply_edit(cards,edit,limit)
            selected = [next(c for c in cards if c["id"] == edit["id"])]
        else:
            wav = wd/"audio.wav"
            ff("-loglevel","error",*input_args(primary["path"]),"-map","0:a:0","-vn","-ar","16000","-ac","1","-c:a","pcm_s16le",wav)
            if mode == "local" and cache.is_file():
                cues = read_json(cache)
            else:
                cues = transcribe(wav,wd,primary["offsetMs"],manifest,blocked)
                require_audio_consent(manifest)
                if mode == "local":
                    draft = wd/"transcript.json"
                    write_json(draft,cues)
                    os.replace(draft,cache)
            cards = make_cards(cues,limit)
            cards = [card for card in cards if not overlapping(card["startMs"], card["endMs"], blocked)]
            attach_context(cards,spec.get("driveContext"))
            require_audio_consent(manifest)
            if mode == "gleo-demo":
                selected = gemini_demo.select(cards, authorize_gemini) if cards else []
                gemini_demo.write_summaries(selected,authorize_gemini)
                authorize_gemini()
                with wave.open(str(wav), "rb") as raw:
                    source_start = primary["offsetMs"]
                    source_end = source_start + (1000 * raw.getnframes()) // raw.getframerate()
                for card in selected:
                    add_surrounding_footage(card, cues, source_start, source_end, blocked, limit)
            else:
                selected = MemoryRanker().select(cards) if cards else []
            if len(selected)<3:
                events=sound_events.detect(wav,primary["offsetMs"],blocked,lambda:require_audio_consent(manifest))
                last_id=max((int(c["id"].split("-")[1]) for c in cards),default=0)
                sound_events.add_cards(selected,events,last_id,limit)
                for card in selected:
                    if card.get("soundEvent"):
                        with wave.open(str(wav),"rb") as raw:
                            end=primary["offsetMs"]+raw.getnframes()*1000//raw.getframerate()
                        others=[(c["startMs"],c["endMs"]) for c in selected if c is not card]
                        add_surrounding_footage(card,cues,primary["offsetMs"],end,blocked+others,limit)
        attach_context(selected,spec.get("driveContext"))
        staging = wd/"release"
        staging.mkdir()
        for card in selected:
            require_audio_consent(manifest)
            if overlapping(card["startMs"],card["endMs"],blocked):
                raise ValueError("Edit overlaps excluded road footage")
            card["file"] = card["id"]+".mp4"
            personal = "access" in spec
            dest = staging / (("master-" if personal else "") + card["file"])
            render(card,tracks,audio_name,wd,dest,road_review.get("blur", []),masks,captions=not personal)
            if personal:
                caption_master(dest,card["startMs"],card,wd,staging/card["file"])
        write_json(staging/"cards.json",dict(tripId=spec["tripId"],profile=profile,version=version,sourceRevision=source_revision,
                   status="READY" if selected else ("NO_MEMORY_CANDIDATE" if cues else "NO_SPEECH_CANDIDATE"),
                   cards=selected,privacy=privacy))
        require_audio_consent(manifest)
        if read_json(manifest).get("roadReview") != road_review:
            raise ValueError("Road footage review changed during processing")
        if mode == "gleo-demo":
            authorize_gemini()
        try:
            staging.rename(target)
        except OSError:
            if not (target/"cards.json").is_file():
                raise
    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest",type=Path)
    parser.add_argument("library",type=Path)
    parser.add_argument("--profile",choices=["STORY_15","SHORT_30"],default="STORY_15")
    parser.add_argument("--edit",type=Path)
    opts = parser.parse_args()
    print(run(opts.manifest,opts.library,opts.profile,opts.edit).name)
