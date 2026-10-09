#!/usr/bin/env python3
"""Verify APK variants and copy a sealed payload for the Linux image builder."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from boot_animation import create as create_boot_animation

ROOT = Path(__file__).resolve().parents[2]


def main(output):
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
    tools = sdk / 'build-tools/36.0.0'
    def read(tool, *args):
        return subprocess.check_output([str(tools / tool), *map(str, args)], text=True)
    def cert(apk):
        return re.search(r'certificate SHA-256 digest: ([a-f0-9]{64})',
                         read('apksigner', 'verify', '--print-certs', apk)).group(1)
    report = {}
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise RuntimeError('Use an empty output directory')
    for module, name, package in [('app', 'assistant.apk', 'com.openodoubao.assistant'),
                                   ('tts', 'tts.apk', 'com.openodoubao.tts')]:
        source = ROOT / 'android' / module / 'build/outputs/apk'
        debug = source / 'debug' / f'{module}-debug.apk'
        system = source / 'system' / f'{module}-system.apk'
        system_cert = cert(system)
        if system_cert != cert(debug):
            raise RuntimeError(f'{module}: system and development signatures differ')
        badging = read('aapt', 'dump', 'badging', system)
        debug_version = int(re.search(r"versionCode='(\d+)'", read('aapt', 'dump', 'badging', debug)).group(1))
        version = int(re.search(r"versionCode='(\d+)'", badging).group(1))
        manifest = read('aapt', 'dump', 'xmltree', system, 'AndroidManifest.xml')
        if f"name='{package}'" not in badging or version <= debug_version:
            raise RuntimeError(f'{module}: incorrect package or version')
        if 'android:debuggable' in manifest or ('android:persistent' in manifest) != (module == 'app'):
            raise RuntimeError(f'{module}: incorrect system manifest')
        shutil.copyfile(system, output / name)
        with system.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        report[name] = dict(sha256=digest, certificate_sha256=system_cert, version_code=version)
    animation = output / 'bootanimation.zip'
    create_boot_animation(animation)
    with animation.open('rb') as stream:
        report[animation.name] = dict(sha256=hashlib.file_digest(stream, 'sha256').hexdigest())
    (output / 'payload.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit('usage: prepare_payload.py OUTPUT_DIRECTORY')
    main(Path(sys.argv[1]))
