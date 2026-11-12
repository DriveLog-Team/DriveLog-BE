import os
import tempfile
import unittest
import wave
from pathlib import Path
import sound_events


class SoundEventTest(unittest.TestCase):
    def test_overlapping_speech_and_sound_do_not_duplicate_a_card(self):
        events=[dict(kind="LAUGHTER",startMs=2000,endMs=3000,confidence=.6),
                dict(kind="BABBLING",startMs=6000,endMs=7500,confidence=.5)]
        selected=[dict(id="moment-001",startMs=1000,endMs=4000)]
        sound_events.add_cards(selected,events,1,15000)
        self.assertEqual(len(selected),2)
        self.assertEqual(selected[1]["soundEvent"]["kind"],"BABBLING")
        self.assertEqual(selected[1]["cues"],[])

    @unittest.skipUnless(Path(os.getenv("YAMNET_MODEL","/opt/road-models/yamnet.tflite")).exists(),"runtime image model")
    def test_runtime_model_rejects_constant_tone(self):
        import numpy as np
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/"tone.wav"
            with wave.open(str(path),"wb") as wav:
                wav.setparams((1,2,16000,0,"NONE",""))
                wav.writeframes((10000*np.sin(np.arange(32000)*2*np.pi*600/16000)).astype("<i2").tobytes())
            self.assertEqual(sound_events.detect(path,0,[],lambda:None),[])
