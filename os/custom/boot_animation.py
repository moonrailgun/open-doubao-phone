#!/usr/bin/env python3
"""Render the existing logo into Android's native, uncompressed boot archive."""
from io import BytesIO
import math
from pathlib import Path
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
WIDTH, HEIGHT, FPS = 1440, 3120, 30
LOGO_SIZE = 512
POSITION = ((WIDTH - LOGO_SIZE) // 2, (HEIGHT - LOGO_SIZE) // 2)


def create(output):
    logo = Image.open(ROOT / 'android/app/src/main/res/drawable-nodpi/app_logo.png').convert('RGB')
    if logo.size != (LOGO_SIZE, LOGO_SIZE):
        raise RuntimeError('Review boot animation layout after changing logo dimensions')
    black = Image.new('RGB', logo.size)
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_STORED) as archive:
        def write(name, content):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
        write('desc.txt', f'{WIDTH} {HEIGHT} {FPS}\np 1 0 part0 #000000\np 0 0 part1 #000000\n')
        for part, count in [('part0', 24), ('part1', 60)]:
            for frame in range(count):
                brightness = frame / (count - 1) if part == 'part0' else 0.96 + 0.04 * math.cos(2 * math.pi * frame / count)
                buffer = BytesIO()
                Image.blend(black, logo, brightness).save(buffer, format='PNG')
                write(f'{part}/{frame:03}.png', buffer.getvalue())
            write(f'{part}/trim.txt', f'{LOGO_SIZE}x{LOGO_SIZE}+{POSITION[0]}+{POSITION[1]}\n' * count)


if __name__ == '__main__':
    import sys
    if len(sys.argv) != 2:
        sys.exit('usage: boot_animation.py OUTPUT_ZIP')
    create(Path(sys.argv[1]))
