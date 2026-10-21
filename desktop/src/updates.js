import { invoke, isTauri } from '@tauri-apps/api/core';

const repo = 'https://github.com/moonrailgun/open-doubao-phone';
const endpoint = 'https://api.github.com/repos/moonrailgun/open-doubao-phone/releases/latest';

function versionParts(version) {
  const match = /^v?(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.exec(version);
  if (!match) throw new Error('无法识别版本号');
  return [...match.slice(1, 4).map(BigInt), match[4] ? 0n : 1n];
}

export function parseRelease(release, context) {
  if (release.draft || release.prerelease) return null;
  const latest = versionParts(release.tag_name);
  const current = versionParts(context.version);
  if (latest[3] === 0n) return null;
  const difference = latest.findIndex((part, i) => part !== current[i]);
  if (difference === -1 || latest[difference] < current[difference]) return null;
  const version = release.tag_name.replace(/^v/, '');
  const arch = { aarch64: 'aarch64', x86_64: 'x64' }[context.arch];
  const suffixes = context.os === 'macos' && arch ? [`${arch}.dmg`]
    : context.os === 'windows' && context.arch === 'x86_64' ? ['x64-setup.exe']
    : context.os === 'linux' && context.arch === 'x86_64' ? ['amd64.deb', 'amd64.AppImage'] : [];
  const assets = Array.isArray(release.assets) ? release.assets : [];
  // GitHub replaces spaces in uploaded filenames with dots.
  const asset = suffixes.flatMap(suffix => assets.filter(asset =>
    asset.name === `Open Doubao_${version}_${suffix}` || asset.name === `Open.Doubao_${version}_${suffix}`))
    .find(asset => typeof asset.browser_download_url === 'string' && asset.browser_download_url.startsWith(`${repo}/releases/download/${release.tag_name}/`));
  return { version, notes: release.body || '此版本未提供更新说明。',
    releaseUrl: `${repo}/releases/tag/${release.tag_name}`, downloadUrl: asset?.browser_download_url || null };
}

export function initUpdates() {
  const $ = id => document.getElementById(id);
  const button = $('update-check');
  const dialog = $('update-dialog');
  let update = null;
  let targetUrl = null;
  let checking = false;

  function showUpdate() {
    $('update-status').textContent = `发现新版本 ${update.version}${update.downloadUrl ? '，下载后打开安装包即可更新。' : '，暂未找到适用于本机的安装包。'}`;
    $('update-notes').textContent = update.notes;
    targetUrl = update.downloadUrl || update.releaseUrl;
    $('update-download').textContent = update.downloadUrl ? '下载安装包' : '查看 Release';
    $('update-download').hidden = false;
  }

  async function check(manual) {
    if (checking) return;
    checking = true;
    button.disabled = true;
    if (manual) {
      $('update-status').textContent = '正在检查更新…';
      $('update-notes').textContent = '';
      $('update-download').hidden = true;
      dialog.showModal();
    }
    try {
      const context = await invoke('get_update_context');
      $('app-version').textContent = `v${context.version}`;
      const response = await fetch(endpoint, { headers: { Accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(15000), cache: 'no-store' });
      if (!response.ok && response.status !== 404) throw new Error(`GitHub HTTP ${response.status}`);
      update = response.status === 404 ? null : parseRelease(await response.json(), context);
      button.textContent = update ? `发现新版本 ${update.version}` : '检查更新';
      if (manual) {
        if (update) showUpdate();
        else $('update-status').textContent = `已是最新版本（${context.version}）`;
      }
    } catch (error) {
      if (manual) $('update-status').textContent = `检查失败：${error.message || error}。请稍后重试。`;
    } finally {
      checking = false;
      button.disabled = false;
    }
  }

  button.addEventListener('click', () => {
    if (update) { showUpdate(); dialog.showModal(); }
    else check(true);
  });
  $('update-download').addEventListener('click', async () => {
    try { await invoke('open_release', { url: targetUrl }); }
    catch (error) { $('update-status').textContent = `无法打开浏览器：${error.message || error}`; }
  });
  if (isTauri()) check(false);
  else button.disabled = true;
}
