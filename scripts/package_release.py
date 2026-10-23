#!/usr/bin/env python3
"""Package a verified unsigned CI build using Shiyi's existing signing identity."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_apk", type=Path)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--apksig", type=Path, required=True, help="Verified apksig 8.7.3 JAR")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()
    policy = json.loads((ROOT / "distribution/signing.json").read_text())
    gradle = (ROOT / "app/build.gradle.kts").read_text()

    def value(pattern):
        matches = re.findall(pattern, gradle)
        if len(matches) != 1:
            raise ValueError("Cannot identify the build version/package unambiguously")
        return matches[0]

    name = value(r'versionName\s*=\s*"([^"]+)"')
    code = int(value(r'versionCode\s*=\s*(\d+)'))
    package = value(r'applicationId\s*=\s*"([^"]+)"')
    if package != policy["applicationId"]:
        raise ValueError("Application ID differs from the installed app")
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:[-.][A-Za-z0-9]+)*", name):
        raise ValueError("Invalid version name")
    baseline = policy["lastDistributedVersionCode"]
    if code < baseline or (name != policy["lastDistributedVersionName"] and code <= baseline):
        raise ValueError("New releases must increase versionCode")
    if name == policy["lastDistributedVersionName"] and code != baseline:
        raise ValueError("A changed versionCode also needs a new versionName")
    for path in (args.input_apk, args.keystore, args.password_file, args.apksig):
        if not path.is_file():
            raise FileNotFoundError(path)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / f"时宜-{name}.apk"
    subprocess.run([
        "java", "-cp", str(args.apksig.resolve()), str(ROOT / "scripts/SignApk.java"),
        str(args.input_apk.resolve()), str(output.resolve()), str(args.keystore.resolve()),
        str(args.password_file.resolve()), policy["certificateSha256"],
    ], check=True)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(".apk.sha256").write_text(f"{digest}  {output.name}\n")
    print(f"Ready: {output}")
    print(f"SHA256: {digest}")


if __name__ == "__main__":
    main()
