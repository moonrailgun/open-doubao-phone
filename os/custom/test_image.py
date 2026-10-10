"""Small disposable ext4 fixture; run inside the image-tools container."""
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from build_image import AVB, ROOT, GROUP_SIZE, OTHER_PARTITIONS, check_capacity, debugfs, install, run


class ImageRecipeTest(unittest.TestCase):
    def test_capacity(self):
        check_capacity(GROUP_SIZE - OTHER_PARTITIONS)
        with self.assertRaises(RuntimeError):
            check_capacity(GROUP_SIZE - OTHER_PARTITIONS + 1)

    def test_default_permissions(self):
        # Only the ROM grants runtime permissions up front; a standalone install still asks in the app.
        (exception,) = ET.parse(ROOT / 'os/custom/default-permissions-douxian.xml').getroot()
        self.assertEqual('com.moonrailgun.opendoubao.assistant', exception.get('package'))
        granted = {permission.get('name'): permission.get('fixed') for permission in exception}
        self.assertEqual(dict.fromkeys(['android.permission.RECORD_AUDIO', 'android.permission.CAMERA',
                                        'android.permission.POST_NOTIFICATIONS'], 'false'), granted)
        manifest = ET.parse(ROOT / 'android/app/src/main/AndroidManifest.xml').getroot()
        declared = {node.get('{http://schemas.android.com/apk/res/android}name') for node in manifest.iter('uses-permission')}
        self.assertLessEqual(set(granted), declared)

    def test_write_and_preserve(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            image = root / 'system.img'
            with image.open('wb') as stream:
                stream.truncate(16 * 1024**2)
            run('mke2fs', '-q', '-t', 'ext4', '-b', '4096', '-O', '^extent,^64bit', '-F', image)
            source = root / 'payload with space'
            source.write_bytes(b'test payload\n')
            install(image, source, '/system/priv-app/Douxian/Douxian.apk')
            self.assertIn('Mode:  0644', debugfs(image, 'stat /system/priv-app/Douxian/Douxian.apk'))
            self.assertIn('security.selinux (26)', debugfs(image, 'ea_list /system/priv-app/Douxian/Douxian.apk'))
            self.assertIn('test payload', debugfs(image, 'cat /system/priv-app/Douxian/Douxian.apk'))
            with self.assertRaises(RuntimeError):
                install(image, source, '/system/priv-app/Douxian/Douxian.apk')
            run('e2fsck', '-fn', image)
            # Two distinct inodes must share a real block to exercise unsharing.
            block = debugfs(image, 'bmap /system/priv-app/Douxian/Douxian.apk 0').splitlines()[-1]
            empty = root / 'empty'
            empty.touch()
            debugfs(image, f'write {empty} /shared', True)
            for field, value in [('size', source.stat().st_size), ('blocks', 8), ('block[0]', block)]:
                debugfs(image, f'set_inode_field /shared {field} {value}', True)
            debugfs(image, 'feature shared_blocks', True)
            with image.open('r+b') as stream:
                stream.truncate(32 * 1024**2)
            run('resize2fs', image)
            run('e2fsck', '-fy', '-E', 'unshare_blocks', image, codes=(0, 1))
            self.assertNotIn('shared_blocks', run('dumpe2fs', '-h', image))
            self.assertIn('test payload', debugfs(image, 'cat /shared'))
            self.assertIn('test payload', debugfs(image, 'cat /system/priv-app/Douxian/Douxian.apk'))
            run('resize2fs', '-M', image)
            run('e2fsck', '-fn', image)
            run(*AVB, 'add_hashtree_footer', '--image', image, '--partition_name', 'system',
                '--partition_size', 40 * 1024**2, '--do_not_generate_fec')
            run(*AVB, 'verify_image', '--image', image)


if __name__ == '__main__':
    unittest.main()
