#!/usr/bin/env python3
"""Publish a new version of the watch app for the LAN self-update.

Copies the release APK to apk/hermes-montre.apk and writes apk/version.json (versionCode,
versionName, sha256, size, notes). The bridge (hermes-voice, ServeurMajMontre) serves both files
from ~/hermes-watch/apk/; watches pick the new version up within 6 hours, or at once from
⚙ › Mise à jour › Rechercher maintenant.

Usage:
    ./gradlew assembleRelease
    python3 tools/publish.py "What changed, in one sentence"
"""
import hashlib
import json
import pathlib
import re
import shutil
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
built = REPO / "app/build/outputs/apk/release/app-release.apk"
out = REPO / "apk"
gradle = (REPO / "app/build.gradle.kts").read_text()
code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
if not built.is_file():
    raise SystemExit(f"APK not found: {built}\nBuild it first: ./gradlew assembleRelease")
out.mkdir(exist_ok=True)
shutil.copyfile(built, out / "hermes-montre.apk")
data = (out / "hermes-montre.apk").read_bytes()
version = {"versionCode": code, "versionName": name, "sha256": hashlib.sha256(data).hexdigest(),
           "taille": len(data), "notes": " ".join(sys.argv[1:]).strip()}
(out / "version.json").write_text(json.dumps(version, ensure_ascii=False, indent=2) + "\n")
print(f"published {name} (code {code}), {len(data)} bytes, sha256 {version['sha256'][:16]}…")
