"""Prepare the next signed catalog and immutable code packages locally."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
IDS = ("android-auto", "carplay", "zeekr-integration", "ui", "autokit")
CATALOG = ROOT / "stable-v5.json"
PUBLIC_KEY = ROOT.parent / "app/src/main/res/raw/module_update_key.der"


def read_catalog(path, temp):
    envelope = json.loads(path.read_bytes())
    signature = temp / "signature"
    signature.write_bytes(base64.b64decode(envelope["signature"], validate=True))
    data = base64.b64decode(envelope["payload"], validate=True)
    subprocess.run(["openssl", "dgst", "-sha256", "-keyform", "DER", "-verify", PUBLIC_KEY,
                    "-signature", signature], input=data, check=True, stdout=subprocess.DEVNULL)
    catalog = json.loads(data)
    if set(catalog["modules"]) != set(IDS):
        raise ValueError("Incomplete module catalog")
    return catalog


def prepare(args, temp):
    previous = read_catalog(CATALOG if CATALOG.exists() else ROOT / "stable-v4.json", temp)
    entries = previous["modules"]
    pending = []
    for name in IDS:
        entry = entries[name]
        revision = entry["revision"]
        directory = ROOT / "packages" / name
        current = (directory / f"{revision}.wasm").read_bytes()
        if hashlib.sha256(current).hexdigest() != entry["sha256"]:
            raise ValueError(f"{name}: existing package differs from its signed digest")
        if args.module and args.module != name:
            continue
        compiled = temp / f"{name}.wasm"
        subprocess.run([args.wat2wasm, ROOT / "src" / name / "policy.wat", "-o", compiled], check=True)
        data = compiled.read_bytes()
        if len(data) > 4096:
            raise ValueError(f"{name}: exceeds runtime package limit")
        if data == current and args.revision in (None, revision):
            continue
        target = args.revision if args.revision is not None else revision + 1
        if target <= revision:
            raise ValueError(f"{name}: expected a new revision after {revision}")
        dest = directory / f"{target}.wasm"
        if dest.exists() and dest.read_bytes() != data:
            raise ValueError(f"{dest}: immutable revision already exists")
        entries[name] = dict(entry, revision=target, sha256=hashlib.sha256(data).hexdigest())
        pending.append((compiled, dest))
        print(f"{name} {revision} -> {target}")
    if previous["abi"] != 5 and args.module:
        raise ValueError("Prepare all five modules together for the first catalog of ABI 5")
    if not pending and previous["abi"] == 5:
        print("All five modules are up to date.")
        return
    if args.check:
        return
    key = Path(os.environ.get("MODULE_SIGNING_KEY", "~/.config/zeekr-module-updates/signing.pem")).expanduser()
    public = subprocess.check_output(["openssl", "pkey", "-in", key, "-pubout", "-outform", "DER"])
    if public != PUBLIC_KEY.read_bytes():
        raise ValueError("Unexpected signing key")
    data = json.dumps(dict(abi=5, modules=entries), separators=(",", ":")).encode()
    signature = subprocess.check_output(["openssl", "dgst", "-sha256", "-sign", key], input=data)
    envelope = {"payload": base64.b64encode(data).decode(), "signature": base64.b64encode(signature).decode()}
    catalog = temp / "catalog.json"
    catalog.write_text(json.dumps(envelope) + "\n")
    for src, dest in pending:
        if not dest.exists():
            src.replace(dest)
    catalog.replace(CATALOG)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("module", choices=IDS, nargs="?")
parser.add_argument("revision", type=int, nargs="?")
parser.add_argument("--wat2wasm", default=os.environ.get("WAT2WASM", "wat2wasm"))
parser.add_argument("--check", action="store_true", help="Show changes without writing packages or signing")
args = parser.parse_args()
lock = os.open(ROOT, os.O_RDONLY)
try:
    fcntl.flock(lock, fcntl.LOCK_EX)
    with tempfile.TemporaryDirectory(dir=ROOT) as temp_dir:
        prepare(args, Path(temp_dir))
finally:
    os.close(lock)
