from io import BytesIO
from pathlib import Path
import tempfile
import unittest
import zipfile

from PIL import Image, ImageChops
from boot_animation import HEIGHT, LOGO_SIZE, POSITION, ROOT, WIDTH, create


class BootAnimationTest(unittest.TestCase):
    def test_android_archive_and_logo(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'bootanimation.zip'
            create(output)
            with zipfile.ZipFile(output) as archive:
                self.assertIsNone(archive.testzip())
                self.assertTrue(all(item.compress_type == zipfile.ZIP_STORED for item in archive.infolist()))
                self.assertEqual(archive.read('desc.txt').decode(),
                                 '1440 3120 30\np 1 0 part0 #FFFFFF\np 0 0 part1 #FFFFFF\n')
                for part, count in [('part0', 24), ('part1', 60)]:
                    frames = [name for name in archive.namelist() if name.startswith(part + '/') and name.endswith('.png')]
                    self.assertEqual(frames, [f'{part}/{frame:03}.png' for frame in range(count)])
                    trims = archive.read(f'{part}/trim.txt').decode().splitlines()
                    self.assertEqual(trims, ['512x512+464+1304'] * count)
                    for name in frames:
                        with Image.open(BytesIO(archive.read(name))) as frame:
                            self.assertEqual(frame.size, (LOGO_SIZE, LOGO_SIZE))
                            frame.verify()
                logo = Image.open(ROOT / 'android/app/src/main/res/drawable-nodpi/app_logo.png').convert('RGB')
                final = Image.open(BytesIO(archive.read('part0/023.png')))
                self.assertIsNone(ImageChops.difference(logo, final).getbbox())
                first = Image.open(BytesIO(archive.read('part0/000.png')))
                self.assertIsNone(ImageChops.difference(first, Image.new('RGB', first.size, 'white')).getbbox())
                self.assertEqual((POSITION[0] * 2 + LOGO_SIZE, POSITION[1] * 2 + LOGO_SIZE), (WIDTH, HEIGHT))
            with self.assertRaises(FileExistsError):
                create(output)


if __name__ == '__main__':
    unittest.main()
