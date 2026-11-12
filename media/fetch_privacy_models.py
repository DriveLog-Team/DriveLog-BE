"""Install Intel Open Model Zoo 2023.0 FP16 files verified against its model.yml SHA-384 digests."""
import hashlib
from pathlib import Path
import sys
import urllib.request

MODELS = {
    "face-detection-retail-0004": ("a7f8d1d41998503c4f3cdd8c12275f04f1736e5142127edcb4c76c3e17188499390574095a5b2a9dd78d3d0f77d02034", "394185d3e42c34d7f9d43229ec8f5755c07e19fd6469d23883e71707fdd8eb66d90cbd62248db90ff3ba1c94adac599b"),
    "vehicle-license-plate-detection-barrier-0106": ("b0a70cfc4ccbe85dd5ba5549c2d142d7f922a0a1116657cdcc9f48cb6afc7cc40a596a655b9e6566396c48b72232ff65", "0406e296c9822f4a5e8ee300ad40e5c0a0eeeaf9206f829556cb836cb8f10a4f8a4dd94d1bb16a60562bf1568fd12618"),
    "person-detection-retail-0013": ("99ad3d4580a0123bef05ff77b6f46ccec16de974d1f5699fb94cd842e3242c6aa641f4977f9a5bb2f0fab42fe51cbb63", "a67422e3b5ec76057651d2a0237eab862de00e968c7eef1e5f333849ae64f91900bcd30a23e1b7dbaa07313e358759b9"),
}

if __name__ == "__main__":
    root = Path(sys.argv[1])
    root.mkdir(parents=True, exist_ok=True)
    for name, checks in MODELS.items():
        for ext, check in zip(("xml", "bin"), checks):
            filename = f"{name}.{ext}"
            url = f"https://storage.openvinotoolkit.org/repositories/open_model_zoo/2023.0/models_bin/1/{name}/FP16/{filename}"
            data = urllib.request.urlopen(url, timeout=60).read()
            if hashlib.sha384(data).hexdigest() != check:
                raise ValueError(f"Model integrity check failed: {filename}")
            (root / filename).write_bytes(data)
