"""Build a signed personal APK, keeping signing material outside the project."""
import json
import os
from pathlib import Path
import secrets
import subprocess

root = Path(__file__).resolve().parents[1]
private = Path.home() / ".local" / "share" / "flixtor-tv"
private.mkdir(parents=True, exist_ok=True, mode=0o700)
private.chmod(0o700)
config = private / "signing.json"
env = os.environ.copy()
if not env.get("JAVA_HOME"):
    java_home = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    if java_home.exists():
        env["JAVA_HOME"] = str(java_home)
if not config.exists():
    password = secrets.token_urlsafe(32)
    values = {"TV_KEYSTORE": str(private / "personal.p12"), "TV_KEY_ALIAS": "personal-tv",
              "TV_STORE_PASSWORD": password, "TV_KEY_PASSWORD": password}
    # Persist before key generation so a failed build never loses its key credentials.
    with open(config, "x", opener=lambda p, flags: os.open(p, flags, 0o600)) as output:
        json.dump(values, output)
else:
    values = json.loads(config.read_text())
env.update(values)
if not Path(values["TV_KEYSTORE"]).exists():
    env.update(values)
    keytool = str(Path(env["JAVA_HOME"]) / "bin" / "keytool") if env.get("JAVA_HOME") else "keytool"
    subprocess.run([keytool, "-genkeypair", "-noprompt", "-storetype", "PKCS12",
                    "-keystore", values["TV_KEYSTORE"], "-alias", values["TV_KEY_ALIAS"],
                    "-storepass:env", "TV_STORE_PASSWORD", "-keypass:env", "TV_KEY_PASSWORD",
                    "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                    "-dname", "CN=Personal TV, OU=Personal, O=Personal, C=IE"], env=env, check=True)
    os.chmod(values["TV_KEYSTORE"], 0o600)
subprocess.run([str(root / "android/gradlew"), ":app:assembleRelease", "--console=plain"], cwd=root / "android", env=env, check=True)
apk = root / "android/app/build/outputs/apk/release/app-release.apk"
import hashlib
apk.with_suffix(".apk.sha256").write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + "  app-release.apk\n")
print("Signed APK: android/app/build/outputs/apk/release/app-release.apk")
print("Signing material: ~/.local/share/flixtor-tv/ — back up securely for future updates.")
