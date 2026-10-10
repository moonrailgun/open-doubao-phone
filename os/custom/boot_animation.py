#!/usr/bin/env python3
"""Package the selected greeting video as a complete Android boot animation."""
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WIDTH, HEIGHT, FPS = 1440, 3120, 30
LOGO_SIZE = 512
POSITION = ((WIDTH - LOGO_SIZE) // 2, (HEIGHT - LOGO_SIZE) // 2)


def create(output):
    with tempfile.TemporaryDirectory() as folder:
        subprocess.run([
            'ffmpeg', '-v', 'error', '-xerror', '-i', str(ROOT / 'os/custom/boot-greeting.mp4'),
            '-map', '0:v:0', '-vf', f'fps={FPS},crop={LOGO_SIZE}:{LOGO_SIZE}:{POSITION[0]}:{POSITION[1]}',
            '-pix_fmt', 'rgb24', '-start_number', '0', str(Path(folder) / '%03d.png'),
        ], check=True)
        frames = sorted(Path(folder).glob('*.png'))
        if len(frames) != 4 * FPS:
            raise RuntimeError('Boot greeting must contain exactly four seconds of frames')
        with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_STORED) as archive:
            def write(name, content):
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.external_attr = 0o100644 << 16
                archive.writestr(info, content)
            # c finishes the greeting even when Android requests an early exit.
            write('desc.txt', f'{WIDTH} {HEIGHT} {FPS}\nc 1 0 part0 #FFFFFF\np 0 0 part1 #FFFFFF\n')
            for part, images in [('part0', frames), ('part1', frames[-1:])]:
                for frame, image in enumerate(images):
                    write(f'{part}/{frame:03}.png', image.read_bytes())
                write(f'{part}/trim.txt', f'{LOGO_SIZE}x{LOGO_SIZE}+{POSITION[0]}+{POSITION[1]}\n' * len(images))


if __name__ == '__main__':
    import sys
    if len(sys.argv) != 2:
        sys.exit('usage: boot_animation.py OUTPUT_ZIP')
    create(Path(sys.argv[1]))
