"""Local YAMNet sound recommendations. Input is mono 16 kHz signed PCM."""
import os
from pathlib import Path
import wave
from extraction_policy import overlapping

MODEL_URL = "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite"
SHA256 = "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"
TITLES = {"BABBLING":"옹알이가 들린 순간", "LAUGHTER":"웃음이 터진 순간"}


def detect(path, offset, blocked, authorize):
    import numpy as np
    try:
        from tflite_runtime.interpreter import Interpreter
    except ImportError:
        from ai_edge_litert.interpreter import Interpreter
    model_file = Path(os.environ.get("YAMNET_MODEL","/opt/road-models/yamnet.tflite"))
    model = Interpreter(model_content=model_file.read_bytes(), num_threads=1)
    model.allocate_tensors()
    inp, out = model.get_input_details()[0], model.get_output_details()[0]
    window = int(inp["shape"][0])
    hop = window//2
    events = []
    with wave.open(str(path),"rb") as wav:
        if (wav.getframerate(),wav.getnchannels(),wav.getsampwidth()) != (16000,1,2):
            raise ValueError("Unexpected audio format")
        for frame in range(0,wav.getnframes(),hop):
            if frame % (hop*16)==0:
                authorize()
            start=offset+frame*1000//16000
            wav.setpos(frame)
            samples=np.frombuffer(wav.readframes(window),dtype="<i2").astype(np.float32)/32768
            end=start+len(samples)*1000//16000
            if len(samples)<window//2 or overlapping(start,end,blocked) or np.max(np.abs(samples))<.002:
                continue
            model.set_tensor(inp["index"],np.pad(samples,(0,window-len(samples))))
            model.invoke()
            scores=model.get_tensor(out["index"])[0]
            laughter=float(max(scores[13:19]))
            score=max(laughter,float(scores[4]))
            if score < .20:
                continue
            kind="BABBLING" if scores[4]>laughter else "LAUGHTER"
            if events and events[-1]["kind"]==kind and start<=events[-1]["endMs"]:
                events[-1]["endMs"]=end
                events[-1]["confidence"]=max(events[-1]["confidence"],score)
            else:
                events.append(dict(kind=kind,startMs=start,endMs=end,confidence=score))
    return events


def add_cards(selected, events, last_id, limit):
    for event in sorted(events,key=lambda e:-e["confidence"]):
        if len(selected)>=3:
            break
        start=event["startMs"]
        end=min(start+limit,event["endMs"])
        if overlapping(start,end,[(c["startMs"],c["endMs"]) for c in selected]):
            continue
        last_id+=1
        selected.append(dict(id=f"moment-{last_id:03}",group=-1,startMs=start,endMs=end,
                             title=TITLES[event["kind"]],summary=TITLES[event["kind"]],cues=[],
                             soundEvent=dict(event,endMs=end)))
