import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from road_privacy import merge, safe_parts, write_mask, scan
from extraction_policy import reviewed_intervals

class RoadTests(unittest.TestCase):
    def test_block_union_and_safe_speech(self):
        blocked = merge([[4000,5200],[800,1700],[1600,2000],[2000,2200]])
        self.assertEqual(list(safe_parts(900,5000,blocked)),[(2200,4000)])
        self.assertEqual(list(safe_parts(1000,2000,blocked)),[])
        self.assertEqual(list(safe_parts(5200,5300,blocked)),[(5200,5300)])
        self.assertEqual(reviewed_intervals({"roadReview":{"complete":True}}),{"complete":True})

    def test_masks_affect_only_matching_panel_and_time(self):
        try: import numpy as np
        except ImportError: self.skipTest("numpy runs in the runtime image")
        with TemporaryDirectory() as tmp:
            p=Path(tmp)/"alpha"
            self.assertTrue(write_mask(p,dict(startMs=80,endMs=200),[(120,160,1,.2,.3,.4,.5)]))
            alpha=np.fromfile(p,dtype=np.uint8).reshape(3,384,216)
            self.assertFalse(alpha[:, :192].any())
            self.assertFalse(alpha[[0,2]].any())
            self.assertEqual(alpha[1,192+76,60],255)
            self.assertEqual(alpha[1,192+76,150],0)

    def test_missing_second_track_fails_entire_scan(self):
        try: import numpy
        except ImportError: self.skipTest("numpy runs in the runtime image")
        with patch("road_privacy.Detector", return_value=lambda frame: []), patch("road_privacy.subprocess.Popen",side_effect=OSError):
            with self.assertRaises(OSError):
                scan({"front":{"offsetMs":0,"path":"missing"}},{},lambda:None)
