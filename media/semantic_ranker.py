"""Rank short Korean conversations against examples of memories and routine speech."""
from file_hash import digest
import os
import re
from pathlib import Path


REVISION = "e5-memory-ko-v2"
MODEL_FILES = {
    "model_qint8_avx512_vnni.onnx": "dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88",
    "tokenizer.json": "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
}

# Short examples in the same style as the ASR text. None are recordings.
MEMORIES = {
    "감사와 위로": ["무서웠을 때 내 손을 잡아 줘서 고마웠어.", "결과가 어떻든 내가 곁에 있어 줄게."],
    "함께한 추억": ["어릴 때 할머니 집 가던 길 생각나? 그때도 우리 둘이 노래했지.",
               "아빠랑 여기서 연을 날리던 날을 아직도 기억해."],
    "처음과 성장": ["엄마 나 오늘 처음으로 혼자 해냈어. 네가 정말 자랑스러워.",
               "우리 딸이 처음으로 아빠라고 불러 줬어."],
    "다시 하고 싶은 약속": ["우리 나중에도 둘이서 이 바다를 보러 오자.",
                     "다음 생일에도 이렇게 다 같이 여행을 가자. 꼭 약속해."],
    "함께 웃은 순간": ["구름이 꼭 할아버지 수염 같아. 그럼 하늘도 면도해야겠네!",
                 "아빠가 길을 잃은 게 아니라 길이 아빠를 잃었대. 그 말에 우리 다 웃었잖아."],
}
ROUTINE = ["다음 교차로에서 우회전하고 두 번째 차선으로 가세요.", "주차비는 카드로 내면 돼. 영수증 받아.",
           "히터 온도 조금 내려 줘. 창문도 닫아 줘.", "내일 회의는 세 시고 서류는 이메일로 보내 주세요.",
           "충전소에 들렀다가 마트에서 물이랑 휴지 사자.", "이용해 주셔서 감사합니다. 잠시 후 안내를 시작합니다.",
           "오늘 날씨는 맑고 바다가 보입니다.", "휴게소 어디야? 다음 출구로 나가면 돼."]

# These cues only disambiguate topic labels of already selected memories.
TOPIC_CUES = {
    "함께한 추억": r"어릴|어렸|어린 시절|하던|주던|했던 기억|그때 생각",
    "다시 하고 싶은 약속": r"(다음에도|내년에도|나중에도|해마다|매년).{0,45}(같이|함께|오자|와요|보자|걸|가자|했으면)|약속하",
    "처음과 성장": r"처음으로|첫걸음|해냈|합격|자랑스러",
    "감사와 위로": r"고마[워웠]|곁에|큰 힘이|옆에.{0,12}(있|앉아)",
    "함께 웃은 순간": r"빵 터|다 웃|웃음바다|깔깔",
}


class MemoryRanker:
    def __init__(self):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer

        directory = Path(os.environ["MEMORY_MODEL_DIR"])
        for name, expected in MODEL_FILES.items():
            if digest(directory / name) != expected:
                raise ValueError(f"Unexpected memory model file: {name}")
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        self.session = ort.InferenceSession(str(directory / next(iter(MODEL_FILES))),
                                            options, providers=["CPUExecutionProvider"])
        self.tokenizer = Tokenizer.from_file(str(directory / "tokenizer.json"))
        self.tokenizer.enable_truncation(max_length=256)
        self.tokenizer.enable_padding(pad_id=self.tokenizer.token_to_id("<pad>"), pad_token="<pad>")
        self.np = np
        self.labels = [label for label, examples in MEMORIES.items() for _ in examples]
        examples = [example for phrases in MEMORIES.values() for example in phrases]
        self.anchors = self.encode(examples + ROUTINE)

    def encode(self, texts):
        # Dynamic int8 activation scales depend on other items in a batch.
        # Encode independently so adding another trip moment cannot change this vector.
        return self.np.stack([self.encode_one(line) for line in texts])

    def encode_one(self, line):
        np = self.np
        encoded = [self.tokenizer.encode("query: " + line)]
        inputs = {"input_ids": np.asarray([e.ids for e in encoded], dtype=np.int64),
                  "attention_mask": np.asarray([e.attention_mask for e in encoded], dtype=np.int64),
                  "token_type_ids": np.asarray([e.type_ids for e in encoded], dtype=np.int64)}
        hidden = self.session.run(None, {i.name: inputs[i.name] for i in self.session.get_inputs()})[0]
        mask = inputs["attention_mask"][:, :, None]
        pooled = (hidden * mask).sum(axis=1) / mask.sum(axis=1)
        return (pooled / np.linalg.norm(pooled, axis=1, keepdims=True))[0]

    def select(self, cards):
        selected = []
        for start in range(0, len(cards), 8):
            batch = cards[start:start + 8]
            vectors = self.encode([" ".join(c["text"] for c in card["cues"]) for card in batch])
            for card, scores in zip(batch, vectors @ self.anchors.T):
                personal = scores[:len(self.labels)]
                index = int(personal.argmax())
                margin = float(personal[index] - scores[len(self.labels):].max())
                if margin < 0.018:
                    continue
                cues = card["cues"]
                text = " ".join(c["text"] for c in cues)
                topic_scores = {label: max(personal[i] for i, name in enumerate(self.labels) if name == label)
                                for label in MEMORIES}
                topics = sorted(topic_scores, key=topic_scores.get, reverse=True)
                nearby = [name for name in topics if topic_scores[topics[0]] - topic_scores[name] < .025]
                supported = [name for name in nearby if re.search(TOPIC_CUES[name], text)]
                label = supported[0] if supported else topics[0]
                if not supported and topic_scores[topics[0]] - topic_scores[topics[1]] < .015:
                    label = "기억할 대화"
                if label in MEMORIES:
                    index = max((i for i, name in enumerate(self.labels) if name == label), key=personal.__getitem__)
                if len(cues) == 1:
                    evidence = cues[0]
                else:
                    cue_scores = self.encode([c["text"] for c in cues]) @ self.anchors.T
                    support = cue_scores[:, index] - cue_scores[:, len(self.labels):].max(axis=1)
                    evidence = cues[int(support.argmax())]
                selected.append(dict(card, score=round(margin, 5), recommendation={
                    "category": label, "method": REVISION,
                    "basis": "sourceTranscript", "quote": evidence["text"],
                    "quoteStartMs": evidence["startMs"], "quoteEndMs": evidence["endMs"],
                }))
        return sorted(selected, key=lambda c: (-c["score"], c["startMs"]))[:3]
