import unittest
from unittest.mock import patch

import gemini_demo


class GeminiDemoTest(unittest.TestCase):
    cards = [{"id": "moment-002", "cues": [
        {"text": "검사받기 겁났지?", "startMs": 100, "endMs": 500},
        {"text": "눈 뜨면 내가 제일 먼저 보일 거야.", "startMs": 550, "endMs": 1200}]}]
    choice = dict(id="moment-002", cue=1, title="수술을 마치고", reasonCode="reassurance")

    def test_evidence_is_copied_from_the_original_cue(self):
        result, = gemini_demo.resolve_choices(self.cards, [self.choice])
        self.assertEqual(result["recommendation"]["quote"], self.cards[0]["cues"][1]["text"])
        self.assertEqual(result["recommendation"]["quoteStartMs"], 550)
        self.assertNotIn("수술", result["title"] + result["recommendation"]["reason"])
        self.assertEqual(gemini_demo.resolve_choices(self.cards, []), [])

    def test_invalid_source_reference_is_not_rendered(self):
        for choices in ([dict(self.choice, cue=-1)], [dict(self.choice, id="made-up")],
                        [self.choice, self.choice]):
            with self.assertRaises(ValueError):
                gemini_demo.resolve_choices(self.cards, choices)

    def test_long_trip_includes_last_window_and_preserves_original_cues(self):
        cards=[{"id":f"moment-{i:03}","cues":[{"text":str(i),"startMs":i*60000,"endMs":i*60000+500}]} for i in range(130)]
        seen=set()
        def rank(group,authorize):
            authorize()
            seen.update(c["id"] for c in group)
            return [dict(c,recommendation=dict(quote=c["cues"][0]["text"],quoteStartMs=c["cues"][0]["startMs"],
                                               quoteEndMs=c["cues"][0]["endMs"])) for c in group[-3:]]
        with patch("gemini_demo.select_batch",side_effect=rank):
            result=gemini_demo.select(cards,lambda:None)
        self.assertEqual(len(seen),130)
        self.assertEqual(result[-1]["cues"],cards[-1]["cues"])

    def test_revocation_before_the_request_prevents_transmission(self):
        def revoked():
            raise ValueError("consent revoked")
        with patch.dict("os.environ", {"GEMINI_API_KEY": "test"}), patch("gemini_demo.urlopen") as network:
            with self.assertRaisesRegex(ValueError, "revoked"):
                gemini_demo.select(self.cards, revoked)
            network.assert_not_called()
