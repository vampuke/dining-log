#!/usr/bin/env python3
"""Assert the built APK identity, signature, source version, and previous release."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from apk_metadata import parse_badging

root = Path(os.environ["GITHUB_WORKSPACE"])
temp = Path(os.environ["RUNNER_TEMP"])
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
apk = root / "android/app/build/outputs/apk/release/app-release.apk"
policy = json.loads((root / ".github/release-policy.json").read_text())
selection = json.loads((temp / "dining-release-selection.json").read_text())

def identity(path):
    badging = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(path)], text=True)
    if path == apk:
        # Retain only public manifest identity fields when the gate fails.
        fields = [line for line in badging.splitlines()
                  if line.startswith(("package: ", "sdkVersion:", "minSdkVersion:", "targetSdkVersion:"))]
        (temp / "dining-apk-badging.txt").write_text("\n".join(fields) + "\n")
    return parse_badging(badging)

def signature(path):
    verified = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(path)], text=True)
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in verified:
        raise RuntimeError("APK v2 signature is required")
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)$", verified, re.M)
    if len(digests) != 1 or digests[0].replace(":", "").upper() != policy["certificate_sha256"]:
        raise RuntimeError("APK signing certificate differs from the fixed release certificate")
    return verified

metadata = identity(apk)
if metadata != {"package": policy["production_package"], "versionCode": selection["versionCode"], "versionName": selection["versionName"]}:
    raise RuntimeError("APK version/package differs from reviewed source")
if "v" + metadata["versionName"] != os.environ["RELEASE_TAG"]:
    raise RuntimeError("APK versionName differs from release tag")
certificate = signature(apk)
(temp / "dining-apk-signature.txt").write_text(certificate)
print(certificate, end="")
if selection["previous"]:
    previous_path = temp / "dining-previous-release.apk"
    previous = identity(previous_path)
    signature(previous_path)
    if previous["package"] != metadata["package"] or metadata["versionCode"] <= previous["versionCode"]:
        raise RuntimeError("APK versionCode must increase from the previous signed release")
else:
    print("Initial signed release: no previous production APK exists for versionCode/upgrade comparison.")
(temp / "dining-apk-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
digest = hashlib.file_digest(apk.open("rb"), "sha256").hexdigest()
apk.with_suffix(".apk.sha256").write_text(f"{digest}  app-release.apk\n")
print(json.dumps({**metadata, "sha256": digest}))

changelog = (root / "CHANGELOG.md").read_text()
heading = re.search(r"^## " + re.escape(metadata["versionName"]) + r"(?:\s|$).*$", changelog, re.M)
if not heading:
    raise RuntimeError("Release changelog section is missing")
notes = changelog[heading.start():]
next_heading = re.search(r"\n## ", notes)
if next_heading:
    notes = notes[:next_heading.start()]
notes += f"\n\nCommit: `{selection['commit']}`\n\nAPK SHA-256: `{digest}`\n"
notes += f"\n<!-- dining-release versionCode={metadata['versionCode']} versionName={metadata['versionName']} commit={selection['commit']} -->\n"
(temp / "dining-release-notes.md").write_text(notes)
