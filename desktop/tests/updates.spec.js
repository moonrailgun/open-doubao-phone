import { test, expect } from '@playwright/test';
import { parseRelease } from '../src/updates.js';

const repo = 'https://github.com/moonrailgun/open-doubao-phone';
const context = { version: '1.9.0', os: 'macos', arch: 'aarch64' };
const names = ['Open Doubao_1.10.0_aarch64.dmg', 'Open Doubao_1.10.0_x64.dmg',
  'Open Doubao_1.10.0_x64-setup.exe', 'Open Doubao_1.10.0_amd64.deb', 'Open Doubao_1.10.0_amd64.AppImage'];
const release = { tag_name: 'v1.10.0', body: '修复连接问题 <script>alert(1)</script>',
  assets: names.map(name => ({ name, browser_download_url: `${repo}/releases/download/v1.10.0/${encodeURIComponent(name)}` })) };

test('compares numeric versions and ignores drafts, previews and older releases', () => {
  expect(parseRelease(release, context).version).toBe('1.10.0');
  for (const version of ['1.10.0', '2.0.0']) expect(parseRelease(release, { ...context, version })).toBeNull();
  expect(parseRelease(release, { ...context, version: '1.10.0-beta.1' })).not.toBeNull();
  expect(parseRelease({ ...release, prerelease: true }, context)).toBeNull();
  expect(parseRelease({ ...release, draft: true }, context)).toBeNull();
  expect(() => parseRelease({ ...release, tag_name: 'not-a-version' }, context)).toThrow();
});

test('selects the correct platform installer and rejects foreign download URLs', () => {
  for (const [os, arch, name] of [['macos', 'aarch64', names[0]], ['macos', 'x86_64', names[1]],
    ['windows', 'x86_64', names[2]], ['linux', 'x86_64', names[3]]]) {
    expect(decodeURIComponent(parseRelease(release, { ...context, os, arch }).downloadUrl)).toContain(name);
  }
  expect(parseRelease({ ...release, assets: [release.assets[4]] }, { ...context, os: 'linux', arch: 'x86_64' }).downloadUrl).toContain('.AppImage');
  const uploaded = { ...release, assets: release.assets.map(asset => ({ ...asset,
    name: asset.name.replaceAll(' ', '.'), browser_download_url: asset.browser_download_url.replaceAll('%20', '.') })) };
  expect(parseRelease(uploaded, context).downloadUrl).toContain('Open.Doubao_1.10.0_aarch64.dmg');
  expect(parseRelease(release, { ...context, os: 'windows', arch: 'aarch64' }).downloadUrl).toBeNull();
  expect(parseRelease({ ...release, assets: [{ name: names[0], browser_download_url: 'https://example.com/installer.dmg' }] }, context).downloadUrl).toBeNull();
});

async function setup(page, response = release, status = 200) {
  await page.addInitScript(context => {
    window.isTauri = true;
    window.openedRelease = null;
    window.__TAURI_INTERNALS__ = { invoke: async (command, args) => {
      if (command === 'get_update_context') return context;
      if (command === 'open_release') { window.openedRelease = args.url; return; }
      if (command === 'get_snapshot') return { listening: true, addresses: [], port: 38687, connected: false, device: null, state: null };
      throw new Error(command);
    } };
  }, context);
  await page.route('https://api.github.com/repos/moonrailgun/open-doubao-phone/releases/latest', route => route.fulfill({ status, json: response }));
  await page.goto('/');
}

test('startup announces updates without interrupting work; download opens the matching installer', async ({ page }) => {
  await setup(page);
  await expect(page.locator('#update-check')).toHaveText('发现新版本 1.10.0');
  await expect(page.locator('#update-dialog')).not.toBeVisible();
  await page.locator('#update-check').click();
  await expect(page.locator('#update-notes')).toHaveText(release.body);
  await expect(page.locator('#update-notes script')).toHaveCount(0);
  await page.locator('#update-download').click();
  await expect.poll(() => page.evaluate(() => window.openedRelease)).toBe(release.assets[0].browser_download_url);
});

test('manual checks show current version and network errors; startup failures stay quiet', async ({ page }) => {
  await setup(page, { message: 'rate limited' }, 403);
  await expect(page.locator('#update-check')).toBeEnabled();
  await expect(page.locator('#update-dialog')).not.toBeVisible();
  await page.locator('#update-check').click();
  await expect(page.locator('#update-status')).toContainText('检查失败');
  await page.locator('#update-dialog .close-dialog').click();
  await page.route('https://api.github.com/repos/moonrailgun/open-doubao-phone/releases/latest', route => route.fulfill({ json: { ...release, tag_name: 'v1.9.0' } }));
  await page.locator('#update-check').click();
  await expect(page.locator('#update-status')).toContainText('已是最新版本');
  await expect(page.locator('#app-version')).toHaveText('v1.9.0');
  await expect(page.locator('#update-download')).toBeHidden();
});
