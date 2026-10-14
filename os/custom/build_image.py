#!/usr/bin/env python3
"""Patch a copy of the pinned GSI in Linux; never flash a device."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
GROUP_SIZE = 8_527_020_032
PRODUCT_PARTITION = 4_315_308_032
# Stock system_ext, product, vendor and vendor_dlkm share the group with system. The GSI never mounts the device's product
# partition (its skip_mount.cfg lists /product), so a layout that leaves it unflashed hands that room to system.
OTHER_PARTITIONS = 443_674_624 + PRODUCT_PARTITION + 651_722_752 + 55_853_056
# The build peaks near the original image plus 4 GiB: the copy grows by that much while blocks are unshared and the
# payload is written, then shrinks back. 10 GB covers the 2.1 GB AOSP base and the 4 GB GMS one is still within reach.
MIN_FREE = 10_000_000_000
# Pixel 6 Pro factory image CP3A.261005.002.A1 (os/README.md): its WebView carries arm64 and arm32 code.
STOCK_PRODUCT = ROOT / 'os/stock-CP3A.261005.002.A1/product.img'
STOCK_WEBVIEW = {
    'WebViewGoogle-Stub/WebViewGoogle-Stub.apk': '2da41b9e13e5b400318f35e33a59ee15bacde1aa8b8466511246dac319cd3c5c',
    'WebViewGoogle/WebViewGoogle.apk.gz': 'e9d67a7c83fe0ee158cc8765fd49134788da11c738d78b6c128ce6d585a27087',
}
AVB = ['python3', '/usr/local/bin/avbtool.py']


def run(*args, codes=(0,)):
    result = subprocess.run(list(map(str, args)), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode not in codes:
        raise RuntimeError(f'{args[0]} exited {result.returncode}:\n{result.stdout}')
    return result.stdout


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def debugfs(image, command, write=False):
    # debugfs can exit 0 on a failed command; callers must verify resulting inodes.
    return run('debugfs', *(['-w'] if write else []), '-R', command, image)


def install(image, source, target, mode='0100644'):
    parts = Path(target).parts
    for end in range(2, len(parts)):
        directory = str(Path(*parts[:end]))
        if 'Inode:' not in debugfs(image, f'stat {directory}'):
            debugfs(image, f'mkdir {directory}', True)
            metadata(image, directory, '040755')
    if 'Inode:' in debugfs(image, f'stat {target}'):
        raise RuntimeError(f'Refusing to replace existing image file: {target}')
    debugfs(image, f'write {json.dumps(str(source))} {target}', True)
    metadata(image, target, mode)
    stat = debugfs(image, f'stat {target}')
    if f'Size: {source.stat().st_size}' not in stat:
        raise RuntimeError(f'Image write failed for {target}: {stat}')
    with tempfile.TemporaryDirectory() as folder:
        extracted = Path(folder) / 'payload'
        debugfs(image, f'dump {target} {extracted}')
        if not extracted.exists() or sha256(extracted) != sha256(source):
            raise RuntimeError(f'Image read-back checksum mismatch: {target}')


def replace_line(image, target, old, new):
    """Rewrite one line of an existing system_file, keeping its mode and owner."""
    stat = debugfs(image, f'stat {target}')
    if 'User:     0   Group:     0' not in stat or 'u:object_r:system_file:s0' not in debugfs(image, f'ea_list {target}'):
        raise RuntimeError(f'Unexpected owner or label: {target}')
    with tempfile.TemporaryDirectory() as folder:
        source = Path(folder) / 'replacement'
        debugfs(image, f'dump {target} {source}')
        lines = source.read_text().splitlines(keepends=True)
        if lines.count(old + '\n') != 1:
            raise RuntimeError(f'Expected exactly one {old!r} in {target}')
        source.write_text(''.join(new + '\n' if line == old + '\n' else line for line in lines))
        debugfs(image, f'rm {target}', True)
        install(image, source, target, '010' + re.search(r'Mode:\s+(\d+)', stat).group(1))


def replace_file(image, source, target):
    """Swap an existing system_file for [source], keeping its mode."""
    stat = debugfs(image, f'stat {target}')
    if 'Inode:' not in stat or 'User:     0   Group:     0' not in stat or \
            'u:object_r:system_file:s0' not in debugfs(image, f'ea_list {target}'):
        raise RuntimeError(f'Unexpected file to replace: {target}')
    debugfs(image, f'rm {target}', True)
    install(image, source, target, '010' + re.search(r'Mode:\s+(\d+)', stat).group(1))


def stamp_version(image, version, build_id):
    """Shows our release in Settings: the build number reads 'Open Doubao v1.27 (CP3A…)', and ro.opendoubao.version carries it alone."""
    if not re.fullmatch(r'v\d+(\.\d+)*(-[a-z0-9]+)?', version):
        raise RuntimeError(f'Version must look like v1.27, got {version!r}')
    old = f'ro.build.display.id={build_id}'
    new = f'ro.build.display.id=Open Doubao {version} ({build_id})'
    # init merges every partition's build.prop and the later product file wins, so each copy of the line changes.
    stamped = 0
    for target in ('/system/build.prop', '/system/system_ext/etc/build.prop', '/system/product/etc/build.prop'):
        if 'Inode:' in debugfs(image, f'stat {target}') and old + '\n' in debugfs(image, f'cat {target}'):
            replace_line(image, target, old, new + (f'\nro.opendoubao.version={version}' if stamped == 0 else ''))
            stamped += 1
    if stamped == 0:
        raise RuntimeError(f'No build.prop carries {old}')


def install_libraries(image, apk, directory):
    """Bundled apps never extract compressed native code; PackageManager loads it from <app>/lib/arm64."""
    with tempfile.TemporaryDirectory() as folder, zipfile.ZipFile(apk) as archive:
        entries = [name for name in archive.namelist() if name.startswith('lib/arm64-v8a/') and name.endswith('.so')]
        if not entries:
            raise RuntimeError(f'No arm64 native libraries in {apk}')
        for name in entries:
            install(image, Path(archive.extract(name, folder)), f'{directory}/lib/arm64/{Path(name).name}')


def metadata(image, target, mode):
    for field, value in [('mode', mode), ('uid', '0'), ('gid', '0')]:
        debugfs(image, f'set_inode_field {target} {field} {value}', True)
    with tempfile.NamedTemporaryFile() as label:
        label.write(b'u:object_r:system_file:s0\0')
        label.flush()
        debugfs(image, f'ea_set -f {label.name} {target} security.selinux', True)
    if 'u:object_r:system_file:s0' not in debugfs(image, f'ea_list {target}'):
        raise RuntimeError(f'Missing SELinux label: {target}')


def check_capacity(system_size, other=OTHER_PARTITIONS):
    if system_size + other > GROUP_SIZE:
        raise RuntimeError(f'System image needs {system_size:,} bytes; available {GROUP_SIZE - other:,}')


def main(output, version=None, base='aosp'):
    """[base] picks the pinned GSI: 'aosp' (os/upstream.json) or 'gms' (os/upstream-gms.json, Google apps included)."""
    output = output.resolve()
    version = version or output.name
    output.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(output).free < MIN_FREE:
        raise RuntimeError('At least 10 GB free is required in the output filesystem')
    upstream = json.loads((ROOT / 'os' / ('upstream.json' if base == 'aosp' else f'upstream-{base}.json')).read_text())
    original = ROOT / 'os' / f'{base}-{upstream["build_id"]}' / 'system.img'
    flash_product = upstream.get('flash_product', True)
    other = OTHER_PARTITIONS - (0 if flash_product else PRODUCT_PARTITION)
    if sha256(original) != upstream['files']['system.img']:
        raise RuntimeError('Original GSI checksum mismatch')
    payload = json.loads((output / 'payload.json').read_text())
    for name in ('assistant.apk', 'tts.apk', 'computer-control-overlay.apk', 'dao.apk', 'doubao-ime.apk', 'bootanimation.zip'):
        if sha256(output / name) != payload[name]['sha256']:
            raise RuntimeError(f'Payload changed after APK verification: {name}')
    image = output / 'system.img'
    if image.exists() or image == original:
        raise RuntimeError('Output system.img already exists; use a fresh output directory')
    print('Copying and expanding GSI', flush=True)
    shutil.copyfile(original, image)
    run(*AVB, 'erase_footer', '--image', image)
    with image.open('r+b') as stream:
        stream.truncate(original.stat().st_size + 4 * 1024**3)
    run('resize2fs', image)
    run('e2fsck', '-fy', '-E', 'unshare_blocks', image, codes=(0, 1))
    if 'shared_blocks' in run('dumpe2fs', '-h', image):
        raise RuntimeError('Shared blocks were not removed')
    if 'Inode:' not in debugfs(image, 'stat /system/build.prop'):
        raise RuntimeError('Unexpected GSI directory layout')
    install(image, output / 'assistant.apk', '/system/priv-app/Douxian/Douxian.apk')
    install(image, output / 'tts.apk', '/system/app/DouxianTts/DouxianTts.apk')
    # PackageManager never scans /system/overlay; the GSI's product overlay folder lives inside system.img.
    install(image, output / 'computer-control-overlay.apk',
            '/system/product/overlay/DouxianComputerControl/DouxianComputerControl.apk')
    install(image, output / 'dao.apk', '/system/app/DaoBrowser/DaoBrowser.apk')
    install(image, output / 'doubao-ime.apk', '/system/app/DoubaoIme/DoubaoIme.apk')
    install_libraries(image, output / 'doubao-ime.apk', '/system/app/DoubaoIme')
    install(image, ROOT / 'os/custom/privapp-permissions-douxian.xml',
            '/system/etc/permissions/privapp-permissions-douxian.xml')
    install(image, ROOT / 'os/custom/douxian.xml', '/system/etc/sysconfig/douxian.xml')
    if base == 'gms':
        # Google modules take over the AOSP module names on a kept /data (original-package); their APEX allowlists only
        # know the Google names, and enforce mode refuses to boot without these copies under the renamed names.
        install(image, ROOT / 'os/custom/privapp-permissions-gms-renamed.xml',
                '/system/etc/permissions/privapp-permissions-gms-renamed.xml')
        # The GSI's WebView (stub plus .apk.gz, inflated by PackageManager at boot) is a multiArch APK with arm64 code
        # only, which Android 17 refuses on this phone because its vendor still advertises 32-bit ABIs; without a WebView
        # the Google sign-in page crashes. The stock Pixel 6 Pro image ships the same Chromium build with both ABIs
        # under the same signing key, so its pair goes in instead.
        with tempfile.TemporaryDirectory() as folder:
            for name, digest in STOCK_WEBVIEW.items():
                source = Path(folder) / Path(name).name
                debugfs(STOCK_PRODUCT, f'dump /app/{name} {source}')
                if not source.exists() or sha256(source) != digest:
                    raise RuntimeError(f'Stock WebView file changed or missing: {name}')
                replace_file(image, source, f'/system/product/app/{name}')
    install(image, ROOT / 'os/custom/default-permissions-douxian.xml',
            '/system/etc/default-permissions/default-permissions-douxian.xml')
    install(image, ROOT / 'os/custom/default-permissions-doubaoime.xml',
            '/system/etc/default-permissions/default-permissions-doubaoime.xml')
    install(image, output / 'bootanimation.zip', '/system/media/bootanimation.zip')
    # ro.product.model comes from the product partition; system_ext keeps the fallback consistent.
    for partition in ('product', 'system_ext'):
        target = f'/system/{partition}/etc/build.prop'
        (old,) = [line for line in debugfs(image, f'cat {target}').splitlines() if line.startswith(f'ro.product.{partition}.model=')]
        replace_line(image, target, old, f'ro.product.{partition}.model=Open Doubao')
    stamp_version(image, version, upstream['build_id'])
    run('e2fsck', '-fy', image, codes=(0, 1))
    run('resize2fs', '-M', image)
    run('e2fsck', '-fn', image)
    fs_size = image.stat().st_size
    partition_size = (fs_size + 4095) // 4096 * 4096
    while True:
        partition_size += 4 * 1024**2
        check_capacity(partition_size, other)
        maximum = int(run(*AVB, 'add_hashtree_footer', '--partition_size', partition_size,
                          '--do_not_generate_fec', '--calc_max_image_size').strip())
        if maximum >= fs_size:
            break
    run(*AVB, 'add_hashtree_footer', '--image', image, '--partition_name', 'system',
        '--partition_size', partition_size, '--do_not_generate_fec', '--hash_algorithm', 'sha256',
        '--salt', upstream['files']['system.img'], '--algorithm', 'NONE',
        '--prop', 'com.android.build.system.os_version:17',
        '--prop', f'com.android.build.system.security_patch:{upstream["security_patch"]}')
    run(*AVB, 'verify_image', '--image', image)
    check_capacity(image.stat().st_size, other)
    (output / 'avb-info.txt').write_text(run(*AVB, 'info_image', '--image', image))
    report = dict(version=version, base=base, display_id=f'Open Doubao {version} ({upstream["build_id"]})',
                  upstream_sha256=upstream['files']['system.img'], system_sha256=sha256(image),
                  system_bytes=image.stat().st_size, group_bytes=GROUP_SIZE, flash_product=flash_product,
                  total_dynamic_bytes=image.stat().st_size + other, payload=payload)
    (output / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    if len(sys.argv) not in (2, 3, 4) or (len(sys.argv) == 4 and sys.argv[3] not in ('aosp', 'gms')):
        sys.exit('usage: build_image.py OUTPUT_DIRECTORY [VERSION such as v1.27; defaults to the directory name] [aosp|gms]')
    main(Path(sys.argv[1]), sys.argv[2] if len(sys.argv) >= 3 else None, sys.argv[3] if len(sys.argv) == 4 else 'aosp')
