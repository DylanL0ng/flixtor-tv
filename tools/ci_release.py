"""Build release metadata using CI secrets; never print signing credentials."""
import base64
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
env = os.environ.copy()
tag = env["GITHUB_REF_NAME"]
version = tag.removeprefix("v")
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    raise ValueError("Stable release tags must use vMAJOR.MINOR.PATCH")
version_code = 1000 + int(env["GITHUB_RUN_NUMBER"])
env.update(TV_VERSION_NAME=version, TV_VERSION_CODE=str(version_code))
with tempfile.TemporaryDirectory() as private:
    key = Path(private) / "release.p12"
    key.write_bytes(base64.b64decode(env.pop("TV_KEYSTORE_BASE64"), validate=True))
    key.chmod(0o600)
    env["TV_KEYSTORE"] = str(key)
    subprocess.run([str(root / "android/gradlew"), ":app:assembleRelease", "--console=plain"], cwd=root / "android", env=env, check=True)
apk = root / "android/app/build/outputs/apk/release/app-release.apk"
metadata = {"versionCode": version_code, "versionName": version, "minSdk": 29,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "commit": env["GITHUB_SHA"],
            "apkUrl": f"https://github.com/DylanL0ng/flixtor-tv/releases/download/{tag}/app-release.apk"}
(root / "release.json").write_text(json.dumps(metadata, indent=2) + "\n")
