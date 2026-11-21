"""Build the signed stable catalog from versioned module profiles in this checkout."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
feed = root / "module-updates"
key = Path(os.environ.get("MODULE_SIGNING_KEY", "~/.config/zeekr-module-updates/signing.pem")).expanduser()
bounds = {
    "android-auto": ("watchdogPollMs", 1000, 2500),
    "carplay": ("firstTcpTimeoutMs", 20000, 60000),
    "zeekr-integration": ("artQuality", 70, 95),
    "ui": ("cardEnterMs", 250, 650),
    "autokit": ("discoveryPollMs", 750, 2500),
}
entries = []
for module, (setting, low, high) in bounds.items():
    path = max((feed / "packages" / module).glob("*.json"), key=lambda p: int(p.stem))
    data = path.read_bytes()
    profile = json.loads(data)
    revision = int(path.stem)
    if (revision < 1 or profile["id"] != module or profile["revision"] != revision
            or type(profile[setting]) is not int or not low <= profile[setting] <= high):
        raise ValueError(path)
    if not (type(profile["minCore"]) is int and type(profile["maxCore"]) is int
            and 0 < profile["minCore"] <= profile["maxCore"] <= 2147483647
            and isinstance(profile["notes"], str) and 0 < len(profile["notes"]) <= 500):
        raise ValueError(f"Invalid compatibility or release notes in {path}")
    entries.append(dict(id=module, revision=revision, minCore=profile["minCore"],
                        maxCore=profile["maxCore"], notes=profile["notes"],
                        sha256=hashlib.sha256(data).hexdigest()))
payload = json.dumps(dict(schema=1, modules=entries), ensure_ascii=False).encode()
public = subprocess.check_output(["openssl", "pkey", "-in", key, "-pubout", "-outform", "DER"])
if public != (root / "app/src/main/res/raw/module_update_key.der").read_bytes():
    raise ValueError("Signing key does not match the application")
signature = subprocess.check_output(["openssl", "dgst", "-sha256", "-sign", key], input=payload)
encode = lambda value: base64.b64encode(value).decode()
output = feed / "stable.tmp"
output.write_text(json.dumps(dict(payload=encode(payload), signature=encode(signature))) + "\n")
output.replace(feed / "stable.json")
