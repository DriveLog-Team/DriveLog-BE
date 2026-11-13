"""Resolve trip-relative bookmarks only within approved, unblocked source audio."""


def add_marked_cards(cards, marks, cues, events, start, end, blocked, limit):
    next_id = max((int(card["id"].split("-")[1]) for card in cards), default=0)
    created = []
    for mark in sorted(marks, key=lambda row: (row["atMs"], row["id"])):
        at = mark["atMs"]
        if not start <= at < end or any(a <= at < b for a, b in blocked):
            continue
        left = max([start] + [b for _, b in blocked if b <= at])
        right = min([end] + [a for a, _ in blocked if a > at])
        # "방금" includes the preceding exchange. Laughter can extend the end within the profile.
        begin = max(left, at - limit * 2 // 3)
        finish = min(right, begin + limit)
        if mark["kind"] == "VOICE_UNTIL_LAUGHTER":
            laughs = [e for e in events if e["kind"] == "LAUGHTER" and
                      at < e["endMs"] <= min(right, at + limit - 1)]
            if laughs:
                finish = min(laughs, key=lambda e: e["endMs"])["endMs"]
                begin = max(left, finish - limit)
        if finish - begin < 250:
            continue
        card = next((c for c in cards if c["startMs"] == begin and c["endMs"] == finish), None)
        if card is None:
            next_id += 1
            lines = [dict(text=c["text"], startMs=max(begin,c["startMs"]), endMs=min(finish,c["endMs"]))
                     for c in cues if c["startMs"] < finish and c["endMs"] > begin]
            title = lines[0]["text"][:48] if lines else "표시한 순간"
            card = dict(id=f"moment-{next_id:03}", group=-1, startMs=begin, endMs=finish,
                        title=title, summary=title, cues=lines)
            cards.append(card)
            created.append(card)
        card.setdefault("markIds", []).append(mark["id"])
    return created
