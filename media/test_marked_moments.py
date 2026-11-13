import unittest
from marked_moments import add_marked_cards


class MarkedMomentsTest(unittest.TestCase):
    def test_marks_respect_road_cuts_and_extend_to_laughter(self):
        marks = [dict(id=k, atMs=t, kind=kind) for k,t,kind in [
            ("early", 50, "TAP"), ("unsafe", 11500, "TAP"), ("a", 20000, "VOICE_UNTIL_LAUGHTER"),
            ("b", 20000, "VOICE_UNTIL_LAUGHTER"), ("silent", 39000, "TAP"), ("outside", 41000, "TAP")]]
        cards = []
        add_marked_cards(cards, marks, [dict(startMs=17000,endMs=18500,text="첫 바다")],
                         [dict(kind="LAUGHTER",startMs=21100,endMs=22500)], 100,40000,[(11000,12000)],15000)
        self.assertEqual([c["markIds"] for c in cards], [["a","b"],["silent"]])
        self.assertEqual((cards[0]["startMs"],cards[0]["endMs"]), (12000,22500))
        self.assertEqual(cards[0]["title"], "첫 바다")
        self.assertEqual(cards[1]["cues"], [])
