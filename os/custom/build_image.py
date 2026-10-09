#!/usr/bin/env python3
"""Patch a copy of the pinned GSI in Linux; never flash a device."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
GROUP_SIZE = 8_527_020_032
OTHER_PARTITIONS = 443_674_624 + 4_315_308_032 + 651_722_752 + 55_853_056
MIN_FREE = 40_000_000_000
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


def install(image, source, target):
    parts = Path(target).parts
    for end in range(2, len(parts)):
        directory = str(Path(*parts[:end]))
        if 'Inode:' not in debugfs(image, f'stat {directory}'):
            debugfs(image, f'mkdir {directory}', True)
            metadata(image, directory, '040755')
    if 'Inode:' in debugfs(image, f'stat {target}'):
        raise RuntimeError(f'Refusing to replace existing image file: {target}')
    debugfs(image, f'write {json.dumps(str(source))} {target}', True)
    metadata(image, target, '0100644')
    stat = debugfs(image, f'stat {target}')
    if f'Size: {source.stat().st_size}' not in stat:
        raise RuntimeError(f'Image write failed for {target}: {stat}')
    with tempfile.TemporaryDirectory() as folder:
        extracted = Path(folder) / 'payload'
        debugfs(image, f'dump {target} {extracted}')
        if not extracted.exists() or sha256(extracted) != sha256(source):
            raise RuntimeError(f'Image read-back checksum mismatch: {target}')


def metadata(image, target, mode):
    for field, value in [('mode', mode), ('uid', '0'), ('gid', '0')]:
        debugfs(image, f'set_inode_field {target} {field} {value}', True)
    with tempfile.NamedTemporaryFile() as label:
        label.write(b'u:object_r:system_file:s0\0')
        label.flush()
        debugfs(image, f'ea_set -f {label.name} {target} security.selinux', True)
    if 'u:object_r:system_file:s0' not in debugfs(image, f'ea_list {target}'):
        raise RuntimeError(f'Missing SELinux label: {target}')


def check_capacity(system_size):
    if system_size + OTHER_PARTITIONS > GROUP_SIZE:
        raise RuntimeError(f'System image needs {system_size:,} bytes; available {GROUP_SIZE - OTHER_PARTITIONS:,}')


def main(output):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(output).free < MIN_FREE:
        raise RuntimeError('At least 40 GB free is required in the output filesystem')
    upstream = json.loads((ROOT / 'os/upstream.json').read_text())
    original = ROOT / 'os' / f'aosp-{upstream["build_id"]}' / 'system.img'
    if sha256(original) != upstream['files']['system.img']:
        raise RuntimeError('Original GSI checksum mismatch')
    payload = json.loads((output / 'payload.json').read_text())
    for name in ('assistant.apk', 'tts.apk', 'bootanimation.zip'):
        if sha256(output / name) != payload[name]['sha256']:
            raise RuntimeError(f'Payload changed after APK verification: {name}')
    image = output / 'system.img'
    if image.exists() or image == original:
        raise RuntimeError('Output system.img already exists; use a fresh output directory')
    print('Copying and expanding GSI', flush=True)
    shutil.copyfile(original, image)
    run(*AVB, 'erase_footer', '--image', image)
    with image.open('r+b') as stream:
        stream.truncate(6 * 1024**3)
    run('resize2fs', image)
    run('e2fsck', '-fy', '-E', 'unshare_blocks', image, codes=(0, 1))
    if 'shared_blocks' in run('dumpe2fs', '-h', image):
        raise RuntimeError('Shared blocks were not removed')
    if 'Inode:' not in debugfs(image, 'stat /system/build.prop'):
        raise RuntimeError('Unexpected GSI directory layout')
    install(image, output / 'assistant.apk', '/system/priv-app/Douxian/Douxian.apk')
    install(image, output / 'tts.apk', '/system/app/DouxianTts/DouxianTts.apk')
    install(image, ROOT / 'os/custom/privapp-permissions-douxian.xml',
            '/system/etc/permissions/privapp-permissions-douxian.xml')
    install(image, ROOT / 'os/custom/douxian.xml', '/system/etc/sysconfig/douxian.xml')
    install(image, output / 'bootanimation.zip', '/system/media/bootanimation.zip')
    run('e2fsck', '-fy', image, codes=(0, 1))
    run('resize2fs', '-M', image)
    run('e2fsck', '-fn', image)
    fs_size = image.stat().st_size
    partition_size = (fs_size + 4095) // 4096 * 4096
    while True:
        partition_size += 4 * 1024**2
        check_capacity(partition_size)
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
    check_capacity(image.stat().st_size)
    (output / 'avb-info.txt').write_text(run(*AVB, 'info_image', '--image', image))
    report = dict(upstream_sha256=upstream['files']['system.img'], system_sha256=sha256(image),
                  system_bytes=image.stat().st_size, group_bytes=GROUP_SIZE,
                  total_dynamic_bytes=image.stat().st_size + OTHER_PARTITIONS, payload=payload)
    (output / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit('usage: build_image.py OUTPUT_DIRECTORY')
    main(Path(sys.argv[1]))
