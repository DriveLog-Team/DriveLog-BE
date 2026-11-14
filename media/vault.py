"""Back up finalized personal clips. Restore into a new, offline private directory only."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

TRIP = re.compile(r"trip-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
ACCOUNT = re.compile(TRIP.pattern[5:])


class Bucket:
    def __init__(self, bucket):
        auth = Request("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token",
                       headers={"Metadata-Flavor": "Google"})
        with urlopen(auth, timeout=5) as res:
            self.token = json.load(res)["access_token"]
        self.base = "https://storage.googleapis.com/storage/v1/b/" + quote(bucket, safe="") + "/o"

    def request(self, method, url, data=None, headers=None):
        headers = {"Authorization": "Bearer " + self.token, **(headers or {})}
        return urlopen(Request(url, data=data, method=method, headers=headers), timeout=40)

    def object(self, name):
        return self.base + "/" + quote(name, safe="")

    def exists(self, name):
        try:
            with self.request("GET", self.object(name) + "?fields=generation"):
                return True
        except HTTPError as exc:
            if exc.code != 404:
                raise
            return False

    def put(self, name, data, size):
        url = self.base.replace("/storage/v1/", "/upload/storage/v1/") + "?" + urlencode(
            {"uploadType": "media", "name": name})
        with self.request("POST", url, data, {"Content-Length": str(size), "Content-Type": "application/octet-stream"}):
            pass

    def delete(self, name):
        try:
            self.request("DELETE", self.object(name)).close()
        except HTTPError as exc:
            if exc.code != 404:
                raise

    def names(self, prefix="items/"):
        page = {}
        while True:
            with self.request("GET", self.base + "?" + urlencode({"prefix": prefix, **page})) as res:
                listing = json.load(res)
            yield from (item["name"] for item in listing.get("items", []))
            if "nextPageToken" not in listing:
                return
            page = {"pageToken": listing["nextPageToken"]}


def backup(bucket, root, doc):
    tid = doc["tripId"]
    if not TRIP.fullmatch(tid) or doc["access"]["phase"] not in ("SAVING", "SAVED"):
        raise ValueError("Only finalized clips can be archived")
    a = doc["access"]
    people = {v for seat in a["seats"] for v in (seat["occupant"], seat.get("guardian")) if v}
    accounts = {}
    for person in people:
        if not ACCOUNT.fullmatch(person):
            raise ValueError("Invalid account")
        digest = (root / "accounts" / person).read_bytes()
        if len(digest) != 32:
            raise ValueError("Invalid account record")
        accounts[person] = base64.b64encode(digest).decode()
    if bucket.exists("revoked/" + tid):
        raise ValueError("Revoked item")
    # Only the selected clip, its permissions and the fields used to view/discard it survive.
    access = {key: a[key] for key in ("owner","seats","receipts","item","selection","usedContext","names","picks")
              if key in a}
    access.update(phase="SAVED", vaultSynced=True)
    spec = {"tripId": tid, "access": access, "extraction": {"occupantCount": len(a["seats"])},
            "audioProcessingConsent": False, "geminiTranscriptConsent": False}
    # Retain timeline links only for the finalized clip.
    if "draftVersion" in a:
        access["draftVersion"] = a["draftVersion"]
        access["markedClips"] = {k:v for k,v in a.get("markedClips", {}).items() if "albumFile" in v or v["id"] == a["selection"]["moment"]}
        spec["marks"] = doc.get("marks", {})
        access["markPlaces"] = a.get("markPlaces", {})
    if "startedAt" in a:
        access["startedAt"] = a["startedAt"]
    clip = root / "trips" / tid / "saved.mp4"
    with clip.open("rb") as source:
        digest = stream_hash(source)
        key = "clips/" + tid
        if not bucket.exists(key):
            source.seek(0)
            bucket.put(key, source, clip.stat().st_size)
    extras = {}
    for name in {v["albumFile"] for v in access.get("markedClips", {}).values() if "albumFile" in v}:
        if not re.fullmatch(r"moment-[0-9]+[.]mp4", name):
            raise ValueError("Invalid album filename")
        file = clip.parent / "album" / name
        with file.open("rb") as source:
            extras[name] = stream_hash(source)
            key = "albums/" + tid + "/" + name
            if not bucket.exists(key):
                source.seek(0)
                bucket.put(key, source, file.stat().st_size)
    payload = json.dumps({"extras": extras, "schema":1,"trip":spec,"accounts":accounts,"sha256":digest}).encode()
    bucket.put("items/" + tid, payload, len(payload))


def stream_hash(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024*1024), b""):
        digest.update(block)
    return digest.hexdigest()


def revoke(bucket, tid):
    if not TRIP.fullmatch(tid):
        raise ValueError("Invalid trip")
    bucket.put("revoked/" + tid, b"1", 1)
    bucket.delete("items/" + tid)
    bucket.delete("clips/" + tid)
    for name in list(bucket.names("albums/" + tid + "/")):
        bucket.delete(name)


def restore(bucket, destination, only=None):
    if destination.exists():
        raise ValueError("Restore requires a new offline directory")
    with tempfile.TemporaryDirectory(dir=destination.parent) as workspace:
        root = Path(workspace) / "private"
        for sub in ("accounts","trips","inbox"):
            (root / sub).mkdir(parents=True)
        for name in ([("items/" + only)] if only else bucket.names()):
            tid = name.removeprefix("items/")
            if not TRIP.fullmatch(tid) or bucket.exists("revoked/" + tid) or not bucket.exists(name):
                continue
            with bucket.request("GET", bucket.object(name) + "?alt=media") as res:
                record = json.load(res)
            doc = record["trip"]
            if record["schema"] != 1 or doc["tripId"] != tid or doc["access"]["phase"] != "SAVED":
                raise ValueError("Invalid vault item")
            folder = root / "trips" / tid
            folder.mkdir()
            with bucket.request("GET", bucket.object("clips/" + tid) + "?alt=media") as res, (folder/"saved.mp4").open("wb") as out:
                shutil.copyfileobj(res, out)
            with (folder/"saved.mp4").open("rb") as clip:
                if stream_hash(clip) != record["sha256"]:
                    raise ValueError("Clip checksum mismatch")
            for filename, expected in record.get("extras", {}).items():
                if not re.fullmatch(r"moment-[0-9]+[.]mp4", filename):
                    raise ValueError("Invalid album filename")
                album = folder / "album"
                album.mkdir(exist_ok=True)
                with bucket.request("GET", bucket.object("albums/" + tid + "/" + filename) + "?alt=media") as res, (album/filename).open("wb") as out:
                    shutil.copyfileobj(res,out)
                with (album/filename).open("rb") as source:
                    if stream_hash(source) != expected:
                        raise ValueError("Album checksum mismatch")
            if bucket.exists("revoked/" + tid):
                shutil.rmtree(folder)
                continue
            for account, encoded in record["accounts"].items():
                value = base64.b64decode(encoded, validate=True)
                if not ACCOUNT.fullmatch(account) or len(value) != 32:
                    raise ValueError("Invalid account record")
                path = root / "accounts" / account
                if path.exists() and path.read_bytes() != value:
                    raise ValueError("Inconsistent account record")
                path.write_bytes(value)
            (folder/"trip.json").write_text(json.dumps(doc), encoding="utf8")
        root.rename(destination)


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("command", choices=("save","revoke","restore"))
    cli.add_argument("bucket")
    cli.add_argument("path", type=Path)
    cli.add_argument("trip", nargs="?")
    args = cli.parse_args()
    os.umask(0o077)
    store = Bucket(args.bucket)
    if args.command == "restore":
        restore(store,args.path,args.trip)
    elif args.command == "revoke":
        revoke(store,args.trip)
    else:
        import sys
        backup(store,args.path,json.load(sys.stdin))
