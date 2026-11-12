import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from file_hash import digest
from worker import intervals, make_cards, apply_edit, plain


def cue(group, start, end, text):
    return dict(group=group, startMs=start, endMs=end, text=text, emphasis=.3)


class ConversationTest(unittest.TestCase):
    def test_source_hash_in_runtime_image(self):
        with TemporaryDirectory() as root:
            source = Path(root, "input")
            source.write_bytes(b"abc")
            self.assertEqual(digest(source), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    def test_silence_including_trailing_silence(self):
        self.assertEqual(intervals(4000, "silence_start: 0\nsilence_end: 4"), [])
        self.assertEqual(intervals(10000, "silence_start: 2\nsilence_end: 5\nsilence_start: 9"),
                         [(0, 2200), (4850, 9200)])

    def test_budget_boundary_preserves_later_complete_segment(self):
        cards = make_cards([cue(1,0,10000,"질문"),cue(1,10000,17000,"답변")],15000)
        self.assertEqual([(c["startMs"],c["endMs"]) for c in cards], [(0,10000),(10000,17000)])

    def test_edit_clips_cues_and_preserves_another_card(self):
        cards = make_cards([cue(1,0,5000,"질문"),cue(1,5000,8500,"답변"),cue(2,13000,15000,"다음")],15000)
        apply_edit(cards,dict(id="moment-001",startMs=5100,captions=["질문","수정"]),15000)
        self.assertEqual(cards[0]["cues"],[dict(startMs=5100,endMs=8500,text="수정")])
        self.assertEqual(cards[1]["title"],"다음")
        with self.assertRaises(ValueError):
            apply_edit(cards,dict(id="moment-001",endMs=12000),15000)

    def test_route_overlay_uses_only_overlapping_events_and_sends_no_places_to_gemini(self):
        import gemini_demo
        from worker import attach_context, subtitles
        cards=make_cards([cue(0,1000,4000,"다음에도 같이 오자")],15000)
        attach_context(cards,dict(departure="서울",destination="속초",events=[
            dict(atMs=2900,kind="ARRIVAL",label="시내 도착"),dict(atMs=4000,kind="LANDMARK",label="다음 장면")]))
        data=gemini_demo.candidate_payload(cards[0])
        self.assertEqual(data["driveEvents"],["ARRIVAL"])
        self.assertNotIn("속초",str(data))
        with TemporaryDirectory() as root:
            file=Path(root,"captions.ass")
            subtitles(cards[0],file)
            output=file.read_text("utf-8-sig")
            self.assertIn("서울 → 속초",output)
            self.assertIn("시내 도착",output)
            self.assertNotIn("다음 장면",output)

    def test_subtitle_text_cannot_inject_ass_commands(self):
        self.assertNotIn("\\", plain(r"{\pos(0,0)}\N"))
        self.assertNotIn("{", plain("{test}"))

    def test_trim_does_not_expose_removed_evidence_in_gleo(self):
        cards = make_cards([cue(0, 0, 3000, "처음 나눈 말"), cue(0, 3500, 6000, "다음 이야기")], 15000)
        cards[0]["recommendation"] = dict(category="함께한 추억", quote="처음 나눈 말",
                                          quoteStartMs=0, quoteEndMs=3000)
        apply_edit(cards, dict(id="moment-001", startMs=3500), 15000)
        self.assertNotIn("recommendation", cards[0])
        self.assertEqual(cards[0]["cues"][0]["text"], "다음 이야기")
