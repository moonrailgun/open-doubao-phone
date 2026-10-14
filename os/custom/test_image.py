"""Small disposable ext4 fixture; run inside the image-tools container."""
from pathlib import Path
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from build_image import AVB, ROOT, GROUP_SIZE, OTHER_PARTITIONS, PRODUCT_PARTITION, STOCK_WEBVIEW, check_capacity, debugfs, install, install_libraries, replace_file, replace_line, run, stamp_version


class ImageRecipeTest(unittest.TestCase):
    def test_capacity(self):
        check_capacity(GROUP_SIZE - OTHER_PARTITIONS)
        with self.assertRaises(RuntimeError):
            check_capacity(GROUP_SIZE - OTHER_PARTITIONS + 1)
        # Without the stock product partition the system image may take its room.
        check_capacity(GROUP_SIZE - OTHER_PARTITIONS + PRODUCT_PARTITION, OTHER_PARTITIONS - PRODUCT_PARTITION)
        with self.assertRaises(RuntimeError):
            check_capacity(GROUP_SIZE - OTHER_PARTITIONS + PRODUCT_PARTITION + 1, OTHER_PARTITIONS - PRODUCT_PARTITION)

    def test_gms_renamed_allowlist(self):
        # On a kept /data the Google permission controller and cell broadcast service are renamed to the AOSP packages;
        # the copies of their APEX allowlists must carry those names, or an enforcing system never boots.
        root = ET.parse(ROOT / 'os/custom/privapp-permissions-gms-renamed.xml').getroot()
        entries = {entry.get('package'): {permission.get('name') for permission in entry} for entry in root}
        self.assertEqual({'com.android.permissioncontroller', 'com.android.cellbroadcastservice'}, set(entries))
        self.assertIn('android.permission.WRITE_SECURE_SETTINGS', entries['com.android.permissioncontroller'])
        self.assertEqual(23, len(entries['com.android.permissioncontroller']))
        self.assertEqual({'android.permission.MODIFY_PHONE_STATE', 'android.permission.READ_PRIVILEGED_PHONE_STATE',
                          'android.permission.RECEIVE_EMERGENCY_BROADCAST', 'android.permission.MANAGE_USERS'},
                         entries['com.android.cellbroadcastservice'])

    def test_gms_layout(self):
        # The GMS GSI outgrows the room next to the stock product partition, so its flash plan leaves product unflashed
        # (zero-length in super) and must otherwise match the AOSP plan line for line.
        gms = json.loads((ROOT / 'os/upstream-gms.json').read_text())
        aosp = json.loads((ROOT / 'os/upstream.json').read_text())
        self.assertFalse(gms['flash_product'])
        self.assertEqual(aosp['build_id'], gms['build_id'])
        self.assertNotEqual(aosp['files']['system.img'], gms['files']['system.img'])
        plan = (ROOT / 'os/fastboot-info.txt').read_text().splitlines()
        self.assertIn('flash product', plan)
        self.assertEqual([line for line in plan if line != 'flash product'],
                         (ROOT / 'os/fastboot-info-gms.txt').read_text().splitlines())

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

    def test_privileged_permissions(self):
        # A privileged permission missing from the allowlist stops an enforcing system from booting.
        (entry,) = ET.parse(ROOT / 'os/custom/privapp-permissions-douxian.xml').getroot()
        manifest = ET.parse(ROOT / 'android/app/src/system/AndroidManifest.xml').getroot()
        declared = {node.get('{http://schemas.android.com/apk/res/android}name') for node in manifest.iter('uses-permission')}
        self.assertEqual({permission.get('name') for permission in entry}, declared)

    def test_replace_line(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            image = root / 'system.img'
            with image.open('wb') as stream:
                stream.truncate(16 * 1024**2)
            run('mke2fs', '-q', '-t', 'ext4', '-b', '4096', '-F', image)
            source = root / 'build.prop'
            source.write_text('ro.product.product.brand=Android\nro.product.product.model=AOSP on ARM64\n')
            target = '/system/product/etc/build.prop'
            install(image, source, target, '0100600')
            replace_line(image, target, 'ro.product.product.model=AOSP on ARM64', 'ro.product.product.model=Open Doubao')
            self.assertTrue(debugfs(image, f'cat {target}').endswith(
                '\nro.product.product.brand=Android\nro.product.product.model=Open Doubao\n'))
            self.assertIn('Mode:  0600', debugfs(image, f'stat {target}'))
            self.assertIn('security.selinux (26)', debugfs(image, f'ea_list {target}'))
            with self.assertRaises(RuntimeError):
                replace_line(image, target, 'ro.product.product.model=AOSP on ARM64', 'unused')
            run('e2fsck', '-fn', image)

    def test_replace_file(self):
        self.assertEqual({'WebViewGoogle-Stub/WebViewGoogle-Stub.apk', 'WebViewGoogle/WebViewGoogle.apk.gz'}, set(STOCK_WEBVIEW))
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            image = root / 'system.img'
            with image.open('wb') as stream:
                stream.truncate(16 * 1024**2)
            run('mke2fs', '-q', '-t', 'ext4', '-b', '4096', '-F', image)
            old, new = root / 'old.apk', root / 'new.apk'
            old.write_bytes(b'arm64 only'); new.write_bytes(b'arm64 and arm32')
            target = '/system/product/app/WebViewGoogle/WebViewGoogle.apk.gz'
            install(image, old, target)
            replace_file(image, new, target)
            self.assertTrue(debugfs(image, f'cat {target}').endswith('\narm64 and arm32'))
            self.assertIn('Mode:  0644', debugfs(image, f'stat {target}'))
            self.assertIn('security.selinux (26)', debugfs(image, f'ea_list {target}'))
            with self.assertRaises(RuntimeError):
                replace_file(image, new, '/system/product/app/WebViewGoogle/missing.apk')
            run('e2fsck', '-fn', image)

    def test_stamp_version(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            image = root / 'system.img'
            with image.open('wb') as stream:
                stream.truncate(16 * 1024**2)
            run('mke2fs', '-q', '-t', 'ext4', '-b', '4096', '-F', image)
            source = root / 'build.prop'
            source.write_text('ro.build.id=CP3A.260905.010\nro.build.display.id=CP3A.260905.010\nro.build.type=user\n')
            install(image, source, '/system/build.prop', '0100600')
            # The product copy loads after the system one and would win, so it must change too.
            install(image, source, '/system/product/etc/build.prop', '0100600')
            stamp_version(image, 'v1.27', 'CP3A.260905.010')
            self.assertIn('\nro.build.display.id=Open Doubao v1.27 (CP3A.260905.010)\nro.opendoubao.version=v1.27\nro.build.type=user\n',
                          debugfs(image, 'cat /system/build.prop'))
            self.assertIn('\nro.build.display.id=Open Doubao v1.27 (CP3A.260905.010)\nro.build.type=user\n',
                          debugfs(image, 'cat /system/product/etc/build.prop'))
            self.assertNotIn('ro.opendoubao.version', debugfs(image, 'cat /system/product/etc/build.prop'))
            with self.assertRaises(RuntimeError):
                stamp_version(image, 'release 1', 'CP3A.260905.010')
            run('e2fsck', '-fn', image)

    def test_install_libraries(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            image = root / 'system.img'
            with image.open('wb') as stream:
                stream.truncate(16 * 1024**2)
            run('mke2fs', '-q', '-t', 'ext4', '-b', '4096', '-F', image)
            apk = root / 'app.apk'
            with zipfile.ZipFile(apk, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('classes.dex', 'dex')
                archive.writestr('lib/arm64-v8a/libime.so', 'arm64 code')
                archive.writestr('lib/armeabi-v7a/libime.so', 'arm32 code')
            install_libraries(image, apk, '/system/app/Ime')
            library = '/system/app/Ime/lib/arm64/libime.so'
            self.assertTrue(debugfs(image, f'cat {library}').endswith('\narm64 code'))
            self.assertIn('Mode:  0644', debugfs(image, f'stat {library}'))
            self.assertIn('security.selinux (26)', debugfs(image, f'ea_list {library}'))
            self.assertIn('security.selinux (26)', debugfs(image, 'ea_list /system/app/Ime/lib/arm64'))
            self.assertNotIn('classes.dex', debugfs(image, 'ls -l /system/app/Ime'))
            run('e2fsck', '-fn', image)
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', 'dex')
            with self.assertRaises(RuntimeError):
                install_libraries(image, apk, '/system/app/Other')

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
