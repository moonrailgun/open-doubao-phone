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
import tempfile
from boot_animation import create as create_boot_animation

ROOT = Path(__file__).resolve().parents[2]
# Prebuilt browser, pinned to the release's published SHA-256.
DAO_APK = ROOT / 'os/dao-browser-android-v0.1.9/dao-browser-0.1.9-android-arm64-v8a.apk'
DAO_SHA256 = '2645fa9570d8de423ccfa9642e4b604e6ed98b3d46d4eec2344c79f0240856e6'
# From shurufa.doubao.com/api/v1/app/download_url?platform=android; no published digest, so pin the file and its signer.
IME_APK = ROOT / 'os/doubao-ime-android-v1.5.1/doubaoime_v1.5.1_100501006_official_arm64_release.apk'
IME_SHA256 = 'abb24d0a33cee91a9d96783243b75950151bc478ce47da629e5fdf8844607f26'
IME_CERT_SHA256 = 'd47cafb450b9c8191180402795f35ce50ce2d8b37f1300b42e7d8633bc86b7df'
OVERLAY_PACKAGE = 'com.moonrailgun.opendoubao.overlay.computercontrol'


def build_overlay(tools, sdk, assistant_cert, target):
    """Static framework RRO naming the assistant, pinned to its certificate, a known Computer Control super agent."""
    with tempfile.TemporaryDirectory() as folder:
        work = Path(folder)
        (work / 'res/values').mkdir(parents=True)
        (work / 'AndroidManifest.xml').write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{OVERLAY_PACKAGE}" android:versionCode="1">
    <overlay android:targetPackage="android" android:isStatic="true" android:priority="1" />
    <application android:hasCode="false" />
</manifest>
''')
        # SigningDetails compares upper-case SHA-256 digests.
        (work / 'res/values/config.xml').write_text(f'''<resources>
    <string-array name="config_computerControlKnownSuperAgents">
        <item>com.moonrailgun.opendoubao.assistant:{assistant_cert.upper()}</item>
    </string-array>
</resources>
''')
        def tool(name, *args):
            subprocess.check_call([str(tools / name), *map(str, args)])
        tool('aapt2', 'compile', '--dir', work / 'res', '-o', work / 'res.zip')
        tool('aapt2', 'link', '-o', work / 'unsigned.apk', '-I', sdk / 'platforms/android-36/android.jar',
             '--manifest', work / 'AndroidManifest.xml', '--min-sdk-version', '30', '--target-sdk-version', '36', work / 'res.zip')
        tool('zipalign', '-f', '-p', '4', work / 'unsigned.apk', work / 'aligned.apk')
        # Same development key as the assistant; the overlay never updates outside the image.
        tool('apksigner', 'sign', '--ks', Path.home() / '.android/debug.keystore', '--ks-pass', 'pass:android',
             '--out', target, work / 'aligned.apk')


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
    for module, name, package in [('app', 'assistant.apk', 'com.moonrailgun.opendoubao.assistant'),
                                   ('tts', 'tts.apk', 'com.moonrailgun.opendoubao.tts')]:
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
    overlay = output / 'computer-control-overlay.apk'
    assistant_cert = report['assistant.apk']['certificate_sha256']
    build_overlay(tools, sdk, assistant_cert, overlay)
    if f'com.moonrailgun.opendoubao.assistant:{assistant_cert.upper()}' not in read('aapt2', 'dump', 'resources', overlay):
        raise RuntimeError('overlay: super agent entry does not match the assistant certificate')
    with overlay.open('rb') as stream:
        report[overlay.name] = dict(sha256=hashlib.file_digest(stream, 'sha256').hexdigest(), certificate_sha256=cert(overlay))
    dao = output / 'dao.apk'
    shutil.copyfile(DAO_APK, dao)
    with dao.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    # Read-only /system cannot extract native code: libraries must load straight from the APK.
    manifest = read('aapt', 'dump', 'xmltree', dao, 'AndroidManifest.xml')
    if digest != DAO_SHA256 or 'extractNativeLibs(0x010104ea)=(type 0x12)0x0' not in manifest:
        raise RuntimeError('dao: unexpected APK')
    read('zipalign', '-c', '-P', '16', '4', dao)
    report[dao.name] = dict(sha256=digest, certificate_sha256=cert(dao))
    ime = output / 'doubao-ime.apk'
    shutil.copyfile(IME_APK, ime)
    with ime.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != IME_SHA256 or cert(ime) != IME_CERT_SHA256:
        raise RuntimeError('doubao-ime: unexpected APK')
    report[ime.name] = dict(sha256=digest, certificate_sha256=IME_CERT_SHA256)
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
