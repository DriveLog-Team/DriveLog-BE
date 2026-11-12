"""Local road-camera detection. All intervals are half-open, in trip milliseconds."""
import math
import os
from pathlib import Path
import subprocess
from fetch_privacy_models import MODELS

POLICY = "omz-road-1"
W, H = 432, 384  # same visible canvas and letterbox as a 1080x960 output panel
STEP = 100


def merge(spans):
    result = []
    for a, b in sorted(spans):
        if result and a <= result[-1][1]:
            result[-1][1] = max(result[-1][1], b)
        else:
            result.append([a, b])
    return result


def safe_parts(start, end, blocked):
    cursor = start
    for a, b in blocked:
        if a >= end:
            break
        if b <= cursor:
            continue
        if cursor < a:
            yield cursor, a
        cursor = max(cursor, b)
    if cursor < end:
        yield cursor, end


class Detector:
    def __init__(self):
        import openvino as ov
        self.nets = []
        core = ov.Core()
        for name in MODELS:
            path = Path(os.getenv("ROAD_MODELS", "/opt/road-models")) / (name + ".xml")
            net = core.compile_model(str(path), "CPU", {"INFERENCE_NUM_THREADS": 1, "NUM_STREAMS": "1"})
            self.nets.append(net)

    def __call__(self, frame):
        import numpy as np
        hits = []
        for kind, net in zip(("face", "plate", "person"), self.nets):
            _, *dims = list(net.input().shape)
            nhwc = dims[-1] == 3
            h, w = dims[:2] if nhwc else dims[1:]
            pixels = frame[(np.arange(h)*H//h)[:, None], np.arange(w)*W//w]
            batch = pixels if nhwc else pixels.transpose(2, 0, 1)
            rows = net([np.ascontiguousarray(batch[None], dtype=np.float32)])[net.output()].reshape(-1, 7)
            for idx, label, conf, *coords in rows:
                if idx < 0:
                    break
                if conf < .45 or int(label) != (2 if kind == "plate" else 1):
                    continue
                x, y, r, b = map(float, np.clip(coords, 0, 1))
                if r > x and b > y:
                    hits.append((kind, x, y, r, b))
        return hits


def scan(tracks, manual, authorize):
    import numpy as np
    detector = Detector()
    masks, auto, cameras = [], [], {}
    for index, key in enumerate(("front", "rear")):
        track = tracks[key]
        offset = track["offsetMs"]
        stats = dict(samples=0, faceHits=0, plateHits=0, nearHits=0)
        cameras[key] = stats
        args = [os.getenv("FFMPEG", "ffmpeg"), "-nostdin", "-v", "error", "-xerror", "-threads", "1",
                "-protocol_whitelist", "file,pipe", "-i", str(track["path"]), "-map", "0:v:0", "-an",
                "-filter_threads", "1", "-vf", f"setpts=PTS-STARTPTS,fps=10:start_time=0:round=up,scale={W}:{H}:force_original_aspect_ratio=decrease,pad={W}:{H}:(ow-iw)/2:(oh-ih)/2",
                "-pix_fmt", "bgr24", "-threads", "1", "-f", "rawvideo", "pipe:1"]
        with subprocess.Popen(args, stdout=subprocess.PIPE) as proc:
            try:
                previous, hits = None, ()
                while True:
                    if stats["samples"] % 100 == 0:
                        authorize()
                    data = proc.stdout.read(W*H*3)
                    if not data:
                        break
                    frame = np.frombuffer(data, dtype=np.uint8).reshape(H,W,3)
                    at = offset + STEP * stats["samples"]
                    stats["samples"] += 1
                    if data != previous:
                        hits = detector(frame)
                        previous = data
                    for kind, x, y, r, b in hits:
                        if kind == "person":
                            if (b-y >= .36 or (b-y >= .22 and b >= .72)):
                                stats["nearHits"] += 1
                                auto.append([max(0,at-600), at+STEP+600])
                        else:
                            stats[kind+"Hits"] += 1
                            dx, dy = max(.025,(r-x)*.35), max(.025,(b-y)*.35)
                            masks.append((at-250, at+STEP+250, index,
                                          max(0,x-dx),max(0,y-dy),min(1,r+dx),min(1,b+dy)))
                if proc.wait() or not stats["samples"]:
                    raise ValueError("Incomplete road camera scan")
            finally:
                if proc.poll() is None:
                    proc.kill()
    blocked = merge(auto + manual.get("nearPedestrian", []))
    report = dict(policy=POLICY, sampleStepMs=STEP, cameras=cameras,
                  excluded=[dict(startMs=a, endMs=b) for a,b in blocked])
    return masks, blocked, report


def write_mask(path, card, masks):
    import numpy as np
    start, end = card["startMs"], card["endMs"]
    relevant = [m for m in masks if m[0] < end and m[1] > start]
    if not relevant:
        return False
    with path.open("wb") as output:
        for t in range(start, end, 40):
            alpha = np.zeros((384,216), np.uint8)
            for a,b,i,x,y,r,v in relevant:
                if a < t+40 and b > t:
                    alpha[i*192+int(y*192):i*192+math.ceil(v*192),int(x*216):math.ceil(r*216)] = 255
            output.write(alpha.tobytes())
    return True
