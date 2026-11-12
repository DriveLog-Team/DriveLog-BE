import unittest
from extraction_policy import require_extraction, reviewed_intervals, overlapping
from worker import add_surrounding_footage


class ExtractionTest(unittest.TestCase):
    def test_all_occupants_and_guardian_must_agree_in_park(self):
        people = [{"ageBand": "14plus", "allowed": True}, {"ageBand": "under14", "allowed": True}]
        gate = {"gear": "P", "stationary": True, "occupantCount": 2, "occupants": people}
        spec = {"audioProcessingConsent": True, "extraction": gate}
        with self.assertRaises(ValueError):
            require_extraction(spec)
        people[1]["guardianAllowed"] = True
        require_extraction(spec)
        for change in ({"gear":"D"}, {"stationary":False}, {"occupantCount":3}):
            with self.assertRaises(ValueError):
                require_extraction(dict(spec, extraction=dict(gate, **change)))

    def test_unreviewed_and_invalid_markers_fail_closed(self):
        for review in ({}, {"complete": True, "blur": [], "nearPedestrian": [[30, 10]]}):
            with self.assertRaises(ValueError):
                reviewed_intervals({"roadReview":review})
        self.assertEqual(overlapping(10, 20, [[4, 11], [20, 22]]), [(10, 11)])

    def test_padding_never_includes_other_speech_or_risky_road(self):
        card = dict(startMs=11000, endMs=18000)
        add_surrounding_footage(card, [dict(startMs=0,endMs=6500)], 0, 30000, [[21000,25000]],15000)
        self.assertEqual(card, dict(startMs=6500, endMs=21000))
