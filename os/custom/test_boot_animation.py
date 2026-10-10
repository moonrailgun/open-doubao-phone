from io import BytesIO
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from PIL import Image, ImageChops
from boot_animation import HEIGHT, LOGO_SIZE, POSITION, ROOT, WIDTH, create


class BootAnimationTest(unittest.TestCase):
    def test_complete_video_then_hold_last_frame(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'bootanimation.zip'
            create(output)
            with zipfile.ZipFile(output) as archive:
                self.assertIsNone(archive.testzip())
                self.assertTrue(all(item.compress_type == zipfile.ZIP_STORED for item in archive.infolist()))
                self.assertEqual(archive.read('desc.txt').decode(),
                                 '1440 3120 30\nc 1 0 part0 #FFFFFF\np 0 0 part1 #FFFFFF\n')
                for part, count in [('part0', 120), ('part1', 1)]:
                    frames = [name for name in archive.namelist() if name.startswith(part + '/') and name.endswith('.png')]
                    self.assertEqual(frames, [f'{part}/{frame:03}.png' for frame in range(count)])
                    trims = archive.read(f'{part}/trim.txt').decode().splitlines()
                    self.assertEqual(trims, ['512x512+464+1304'] * count)
                    for name in frames:
                        with Image.open(BytesIO(archive.read(name))) as frame:
                            self.assertEqual(frame.size, (LOGO_SIZE, LOGO_SIZE))
                            frame.verify()
                self.assertEqual(archive.read('part1/000.png'), archive.read('part0/119.png'))
                subprocess.run([
                    'ffmpeg', '-v', 'error', '-xerror', '-i', str(ROOT / 'os/custom/boot-greeting.mp4'),
                    '-vf', r'select=eq(n\,0)+eq(n\,60)+eq(n\,119)', '-fps_mode', 'passthrough',
                    str(Path(folder) / 'source-%d.png'),
                ], check=True)
                x, y = POSITION
                for index, frame in enumerate((0, 60, 119), start=1):
                    with Image.open(Path(folder) / f'source-{index}.png') as source:
                        self.assertEqual(source.size, (WIDTH, HEIGHT))
                        expected = source.convert('RGB').crop((x, y, x + LOGO_SIZE, y + LOGO_SIZE))
                    actual = Image.open(BytesIO(archive.read(f'part0/{frame:03}.png'))).convert('RGB')
                    self.assertIsNone(ImageChops.difference(expected, actual).getbbox())
                self.assertEqual((POSITION[0] * 2 + LOGO_SIZE, POSITION[1] * 2 + LOGO_SIZE), (WIDTH, HEIGHT))
            with self.assertRaises(FileExistsError):
                create(output)


if __name__ == '__main__':
    unittest.main()
