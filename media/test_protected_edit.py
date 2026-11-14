import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from worker import run


class ProtectedClipTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which(os.environ.get("FFMPEG", "ffmpeg")), "requires FFmpeg")
    def test_reedit_after_original_files_are_deleted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            canonical="a"*32
            library=root/"work"
            export=library/canonical
            export.mkdir(parents=True)
            manifest=root/"trip.json"
            manifest.write_text(json.dumps(dict(tripId="trip-test",audioProcessingConsent=True,
                extraction=dict(gear="P",stationary=True,occupantCount=1,occupants=[dict(ageBand="14plus",allowed=True)]),
                roadReview=dict(complete=True),access=dict(draftVersion=canonical))))
            master=export/"master-moment-001.mp4"
            ffmpeg=os.environ.get("FFMPEG","ffmpeg")
            subprocess.run([ffmpeg,"-loglevel","error","-f","lavfi","-i","color=blue:s=360x640:r=25",
                "-f","lavfi","-i","sine=frequency=420","-t","2","-c:v","libx264","-c:a","aac",str(master)],check=True)
            card=dict(id="moment-001",startMs=5000,endMs=7000,title="Original",summary="Original",
                      driveContext=dict(departure="PrivateRoute",destination="PrivateDestination"),
                      shareMasks=[dict(x=10,y=10,width=40,height=40)],
                      cues=[dict(startMs=5000,endMs=7000,text="Caption")],file="moment-001.mp4")
            (export/"cards.json").write_text(json.dumps(dict(version=canonical,sourceRevision="source",profile="STORY_15",cards=[card])))
            edit=root/"edit.json"
            edit.write_text(json.dumps(dict(baseVersion=canonical,id=card["id"],startMs=5400,endMs=6600,title="New title")))
            edited=run(manifest,library,"STORY_15",edit)
            result=json.loads((edited/"cards.json").read_text())["cards"][0]
            self.assertEqual((result["title"],result["startMs"],result["endMs"]),("New title",5400,6600))
            decoded=subprocess.check_output([ffmpeg,"-loglevel","error","-i",str(edited/card["file"]),
                "-map","0:a:0","-ar","8000","-ac","1","-f","s16le","-"])
            self.assertAlmostEqual(len(decoded)/16000,1.2,delta=.05)
            # The same blue source remains visible privately. The requested block is black in the shared copy.
            def pixel(file):
                return subprocess.check_output([ffmpeg,"-v","error","-i",str(file),"-frames:v","1",
                    "-vf","crop=2:2:20:20,format=rgb24","-f","rawvideo","-"])[:3]
            self.assertGreater(pixel(edited/card["file"])[2], 180)
            self.assertLess(max(pixel(edited/("share-"+card["file"]))), 20)
            self.assertFalse((root/"raw").exists())
