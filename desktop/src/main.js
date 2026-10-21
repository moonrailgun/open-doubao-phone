import { invoke, isTauri } from '@tauri-apps/api/core';
import { initUpdates } from './updates.js';
import QRCode from 'qrcode';
import { createElement, createIcons, Check, CircleAlert, Copy, Link2, Link2Off, ListTodo, Network, Play, RefreshCw, Send, Smartphone, SmartphoneNfc, Square, Trash2, X } from 'lucide';
import './style.css';

const $ = (id) => document.getElementById(id);
const icon = (node) => createElement(node, { class: 'lucide', 'aria-hidden': 'true', focusable: 'false' });
createIcons({ icons: { Link2, Link2Off, ListTodo, Network, Play, RefreshCw, Send, Smartphone, SmartphoneNfc, Square, Trash2, X }, attrs: { 'aria-hidden': 'true', focusable: 'false' } });
const labels = { IDLE: '等待任务', RUNNING: '正在执行', PAUSED: '已暂停', COMPLETED: '已完成', SUCCEEDED: '已完成', DONE: '已完成', FAILED: '执行失败', STOPPED: '已停止', CANCELLED: '已停止', INTERRUPTED: '已中断' };
let snapshot = { connected: false, device: null, state: null, addresses: [] };
let sending = false;
let commanding = false;
let refreshing = false;
let recordKey = '';
let session = 0;
let detailRequest = 0;
let unmountTimeline = () => {};
let expiresAt = 0;
let pairingRequest = 0;
let historyRecords = [];
let historyRange = '';
let modelConfig = null;
let modelSaving = false;
let modelTesting = false;
let modelRequest = 0;
let appAccess = null;
let appsSaving = false;
let appsRequest = 0;
let ttsConfig = null;
let shownRecord = null;
let deleting = false;
const recordHeight = 96;

const message = (error) => typeof error === 'string' ? error : error?.message || '操作失败，请检查连接后重试。';
const date = (time) => new Date(time).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
function notice(text, error = false) {
  $('notice').textContent = text;
  $('notice').hidden = !text;
  $('notice').classList.toggle('error', error);
}
function element(tag, text, className) {
  const el = document.createElement(tag);
  if (text != null) el.textContent = text;
  if (className) el.className = className;
  return el;
}
// The phone's Markdown.kt subset, built from text nodes so device output never becomes HTML.
// Italic avoids lookbehind, which older macOS WebViews cannot parse.
const inline = /\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)\]\((\S+?)\)|(^|[^\w*])\*([^\s*](?:.*?[^\s*])??)\*(?![\w*])/g;
const heading = /^#{1,6}\s+/;
const bullet = /^(\s*)[-*+]\s+/;
const rule = /^\s*(?:[-*_]\s*){3,}$|^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)*\|?\s*$/;
function markdown(source) {
  const out = document.createDocumentFragment();
  source.trim().split(/\r\n|\r|\n/).filter((line) => !rule.test(line)).forEach((raw, index) => {
    if (index) out.append('\n');
    let line = raw.replace(heading, '').replace(bullet, '$1• ');
    if (line.trimStart().startsWith('|')) line = line.trim().replace(/^\|+|\|+$/g, '').split('|').map((cell) => cell.trim()).join('  |  ');
    const target = heading.test(raw) ? out.appendChild(element('strong', null, 'md-heading')) : out;
    let last = 0;
    for (const match of line.matchAll(inline)) {
      const [whole, bold, code, label, url, before = '', italic] = match;
      target.append(line.slice(last, match.index) + before);
      if (bold) target.append(element('strong', bold));
      else if (code) target.append(element('code', code));
      else if (label) target.append(label === url ? url : `${label} (${url})`);
      else target.append(element('em', italic));
      last = match.index + whole.length;
    }
    target.append(line.slice(last));
  });
  return out;
}
function resetHistory(text) {
  historyRecords = [];
  historyRange = '';
  $('records').replaceChildren(element('p', text, 'history-empty'));
  $('records').scrollTop = 0;
}
function renderHistory() {
  if (!historyRecords.length) return;
  const list = $('records');
  const start = Math.max(0, Math.floor(list.scrollTop / recordHeight) - 2);
  const end = Math.min(historyRecords.length, Math.ceil((list.scrollTop + list.clientHeight) / recordHeight) + 2);
  const focused = list.contains(document.activeElement) ? document.activeElement.dataset.index : undefined;
  const range = `${start}:${end}:${focused ?? ''}`;
  if (historyRange === range) return;
  historyRange = range;
  const space = element('div', null, 'records-space');
  space.style.height = `${historyRecords.length * recordHeight}px`;
  // Preserve keyboard focus while the visible window moves.
  for (let i = 0; i < historyRecords.length; i += 1) {
    if ((i < start || i >= end) && String(i) !== focused) continue;
    const record = historyRecords[i];
    const button = element('button', null, 'record-row');
    button.dataset.index = i;
    button.style.top = `${i * recordHeight}px`;
    button.style.height = `${recordHeight}px`;
    button.append(element('span', record.goal, 'record-goal'));
    const meta = element('span', null, 'record-meta');
    meta.append(element('span', labels[record.status] || record.status, record.status === 'FAILED' ? 'failed' : ''), element('time', date(record.time)));
    button.append(meta);
    button.addEventListener('click', () => showRecord(record.id));
    space.append(button);
  }
  list.replaceChildren(space);
  if (focused !== undefined) list.querySelector(`[data-index="${focused}"]`)?.focus({ preventScroll: true });
}
$('records').addEventListener('scroll', renderHistory, { passive: true });
new ResizeObserver(renderHistory).observe($('records'));
function addCopyButton(target, label) {
  const box = element('div', null, `copy-box${target.classList.contains('log') ? ' log-box' : ''}`);
  const button = element('button', null, 'copy-button');
  const status = element('span', '', 'sr-only');
  status.setAttribute('role', 'status');
  button.type = 'button';
  button.setAttribute('aria-label', label);
  button.title = label;
  button.append(icon(Copy), status);
  target.replaceWith(box);
  box.append(button, target);
  let reset;
  button.addEventListener('click', async () => {
    const text = target.value ?? target.textContent;
    clearTimeout(reset);
    try {
      try { await navigator.clipboard.writeText(text); }
      catch {
        // Some native WebViews do not expose the Clipboard API.
        const input = element('textarea', null, 'clipboard-input');
        input.value = text;
        box.append(input);
        input.select();
        try { if (!document.execCommand('copy')) throw new Error('Copy failed'); }
        finally { input.remove(); button.focus({ preventScroll: true }); }
      }
      button.firstElementChild.replaceWith(icon(Check));
      status.textContent = '已复制';
      button.title = '已复制完整内容';
    } catch {
      button.firstElementChild.replaceWith(icon(CircleAlert));
      status.textContent = '复制失败';
      button.title = '复制失败，请选中文字后使用系统复制快捷键。';
    }
    reset = setTimeout(() => { button.firstElementChild.replaceWith(icon(Copy)); status.textContent = ''; button.title = label; }, 2500);
  });
  return button;
}
addCopyButton($('goal'), '复制任务内容');
addCopyButton($('current-result'), '复制回复与总结');
addCopyButton($('live-log'), '复制执行日志');
addCopyButton($('pair-code'), '复制设备码').id = 'copy-code';
function controls() {
  const running = snapshot.state?.status === 'RUNNING';
  const paused = snapshot.state?.status === 'PAUSED';
  $('send').disabled = !snapshot.connected || running || paused || sending || !$('goal').value.trim();
  $('send-label').textContent = sending ? '正在下发…' : '下发任务';
  $('stop').hidden = !running && !paused;
  $('stop').disabled = !snapshot.connected || commanding;
  $('resume').hidden = !paused;
  $('resume').disabled = !snapshot.connected || commanding;
  $('refresh').disabled = !snapshot.connected || refreshing;
  $('model-open').disabled = !snapshot.connected;
  $('model-fields').disabled = !snapshot.connected || !modelConfig || modelSaving || running || paused;
  $('tts-fields').disabled = !snapshot.connected || !ttsConfig || modelSaving;
  $('model-save').disabled = $('tts-fields').disabled || modelTesting;
  $('model-save').textContent = modelSaving ? '正在保存…' : running || paused ? '仅保存语音设置' : '保存到手机';
  $('model-test').disabled = $('model-fields').disabled || modelTesting;
  $('model-test').textContent = modelTesting ? '正在测试…' : '测试连接';
  $('apps-open').disabled = !snapshot.connected;
  $('apps-fields').disabled = !snapshot.connected || !appAccess || appsSaving || running || paused;
  $('apps-save').disabled = $('apps-fields').disabled || !appAccess.selected.size;
  $('apps-save').textContent = appsSaving ? '正在保存…' : running || paused ? '请先停止当前任务' : '保存到手机';
  $('record-delete').hidden = !shownRecord;
  $('record-delete').disabled = !snapshot.connected || deleting || running || paused;
  $('record-delete').title = running || paused ? '请先停止当前任务' : '';
  if (modelConfig) {
    $('jev-key').required = modelConfig.hasJevKey && Boolean($('jev-endpoint').value.trim()) && $('jev-endpoint').value.trim() !== modelConfig.jevEndpoint;
    $('chat-key').required = !modelConfig.hasChatKey || $('chat-endpoint').value.trim() !== modelConfig.chatEndpoint;
  }
  if (ttsConfig) {
    const endpoint = $('tts-endpoint').value.trim();
    $('tts-token').required = ttsConfig.hasToken && endpoint !== '' && endpoint !== ttsConfig.endpoint;
  }
  $('compose-hint').textContent = !snapshot.device ? '绑定手机后即可下发任务' : !snapshot.connected ? '手机未连接，请检查网络与接收开关' : paused ? '任务已暂停，在手机处理后点击继续' : running ? '手机正在执行任务，完成后可继续下发' : '手机需保持解锁，使用手机端已保存的应用范围';
}
function render(next) {
  const old = snapshot;
  snapshot = next;
  const changedDevice = old.device?.id !== next.device?.id;
  if (changedDevice || old.connected !== next.connected) {
    session += 1;
    recordKey = '';
    resetHistory(next.connected ? '正在读取记录…' : '连接手机后读取任务记录。');
    detailRequest += 1;
    $('record-dialog').close();
    $('model-dialog').close();
    clearModelConfig();
    $('apps-dialog').close();
    clearApps();
  }
  $('device-name').textContent = next.device?.name || '尚未绑定设备';
  $('connection-status').textContent = next.connected ? '已连接' : next.device ? '等待手机连接' : next.listening ? '可以绑定手机' : '连接服务未启动';
  $('connection-dot').classList.toggle('online', next.connected);
  $('pair-open').hidden = Boolean(next.device);
  $('pair-open').disabled = !next.listening;
  $('forget').hidden = !next.device;
  $('server-address').textContent = next.addresses.length ? `${next.addresses.join(' / ')} : ${next.port}` : '未找到局域网地址，请连接 Wi-Fi 或有线网络。';
  if (next.device && $('pair-dialog').open) {
    clearPairing();
    $('pair-dialog').close();
    notice('手机已绑定。现在可以下发任务和读取记录。');
  }
  if (next.error && next.error !== old.error) notice(next.error, true);
  const state = next.state;
  $('task-empty').hidden = Boolean(state?.taskId);
  $('task-content').hidden = !state?.taskId;
  $('task-badge').textContent = labels[state?.status] || state?.status || '等待任务';
  $('task-badge').classList.toggle('running', state?.status === 'RUNNING');
  if (state) {
    $('current-goal').textContent = state.goal || '';
    $('current-status').textContent = labels[state.status] || state.status;
    $('current-steps').textContent = `${state.steps || 0} 步`;
    $('current-message').textContent = state.message || '';
    // Rebuild only on change so a selection in the summary survives polling.
    if ($('current-result').dataset.source !== (state.result || '')) {
      $('current-result').dataset.source = state.result || '';
      $('current-result').replaceChildren(markdown(state.result || ''));
    }
    $('result-block').hidden = !state.result;
    const log = state.log || [];
    const text = log.join('\n');
    if ($('live-log').textContent !== text) {
      const follow = $('live-log').scrollTop + $('live-log').clientHeight >= $('live-log').scrollHeight - 40;
      $('live-log').textContent = text;
      if (follow) $('live-log').scrollTop = $('live-log').scrollHeight;
    }
    $('log-count').textContent = `${log.length} 条`;
  }
  controls();
  const nextKey = `${next.device?.id}:${state?.taskId}:${state?.status}`;
  if (next.connected && recordKey !== nextKey && !refreshing) {
    recordKey = nextKey;
    refreshRecords();
  }
}
async function refreshRecords() {
  if (!snapshot.connected || refreshing) return;
  const currentSession = session;
  refreshing = true;
  controls();
  try {
    const records = await invoke('request', { method: 'records.list', params: {} });
    if (currentSession !== session) return;
    resetHistory('还没有记录。下发一个任务，完成后会保存在手机上。');
    historyRecords = records;
    renderHistory();
  } catch (error) {
    if (currentSession === session) {
      resetHistory('读取失败，点击刷新重试。');
      notice(`读取记录失败：${message(error)}`, true);
    }
  } finally {
    refreshing = false;
    controls();
  }
}
async function showRecord(taskId) {
  const current = ++detailRequest;
  const currentSession = session;
  shownRecord = null;
  $('record-message').textContent = '';
  controls();
  unmountTimeline();
  $('record-content').replaceChildren(element('p', '正在读取…', 'muted'));
  $('record-dialog').showModal();
  try {
    const record = await invoke('request', { method: 'records.get', params: { taskId } });
    if (current !== detailRequest || currentSession !== session) return;
    shownRecord = taskId;
    controls();
    const result = element('div', null, 'prose');
    result.append(record.result ? markdown(record.result) : '暂无结果');
    const log = element('pre', (record.log || []).join('\n') || '暂无日志', 'log');
    const logHeading = element('h2', '执行与访问日志');
    $('record-content').replaceChildren(element('h3', record.goal), element('p', `${labels[record.status] || record.status} · ${date(record.time)}`, 'muted'), result, logHeading, log);
    // Desktop only: the phone keeps the plain log. React and the waterfall load on first use.
    import('./timeline.js').then(({ renderTimeline }) => {
      if (current !== detailRequest) return;
      const box = element('div', null, 'timeline');
      const unmount = renderTimeline(box, record.log);
      if (!unmount) return;
      unmountTimeline = unmount;
      logHeading.before(element('h2', '执行时间线'), box);
    }).catch((error) => console.error('时间线加载失败', error));
    addCopyButton(result, '复制任务结果');
    addCopyButton(log, '复制执行与访问日志');
    const download = element('button', '下载模型调用记录（zip）', 'button');
    const hint = element('p', '包含这条任务完整的模型请求与响应：提示词、屏幕文字和截图。保存到「下载」文件夹，便于排查或交给 AI 分析；只分享给可信的人。', 'muted');
    download.addEventListener('click', async () => {
      download.disabled = true;
      $('record-message').textContent = '';
      hint.textContent = '正在从手机下载…';
      try {
        hint.textContent = `已保存：${await invoke('download_calls', { taskId })}`;
      } catch (error) {
        hint.textContent = '';
        $('record-message').textContent = `下载失败：${message(error)}`;
      } finally { download.disabled = false; }
    });
    $('record-content').append(element('h2', '模型调用记录'), download, hint);
  } catch (error) {
    if (current === detailRequest) $('record-content').replaceChildren(element('p', message(error), 'error'));
  }
}
async function poll() {
  try {
    render(await invoke('get_snapshot'));
  } catch (error) {
    render({ ...snapshot, connected: false, listening: false, error: `桌面连接服务不可用：${message(error)}` });
  } finally {
    window.setTimeout(poll, 1000);
  }
}
$('goal').addEventListener('input', controls);
$('record-dialog').addEventListener('click', (event) => {
  if (event.target !== event.currentTarget) return;
  const { left, right, top, bottom } = event.currentTarget.getBoundingClientRect();
  if (event.clientX < left || event.clientX > right || event.clientY < top || event.clientY > bottom) event.currentTarget.close();
});
$('record-dialog').addEventListener('close', () => { detailRequest += 1; shownRecord = null; unmountTimeline(); controls(); });
$('record-delete').addEventListener('click', async () => {
  const taskId = shownRecord;
  const currentSession = session;
  if (!taskId || $('record-delete').disabled) return;
  deleting = true;
  $('record-message').textContent = '';
  controls();
  try {
    await invoke('request', { method: 'records.delete', params: { taskId } });
    if (currentSession !== session) return;
    $('record-dialog').close();
    notice('记录已从手机删除。');
    recordKey = '';
    refreshRecords();
  } catch (error) { if (shownRecord === taskId) $('record-message').textContent = `删除失败：${message(error)}`; }
  finally { deleting = false; controls(); }
});
$('task-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if ($('send').disabled) return;
  sending = true;
  controls();
  notice('');
  try {
    await invoke('request', { method: 'task.start', params: { goal: $('goal').value.trim() } });
    notice('任务已由手机接收。');
    render(await invoke('get_snapshot'));
  } catch (error) { notice(message(error), true); }
  finally { sending = false; controls(); }
});
async function command(method, success) {
  const taskId = snapshot.state?.taskId;
  if (!taskId || !snapshot.connected || commanding) return;
  commanding = true;
  controls();
  try {
    await invoke('request', { method, params: { taskId } });
    notice(success);
    render(await invoke('get_snapshot'));
  } catch (error) { notice(message(error), true); }
  finally { commanding = false; controls(); }
}
$('stop').addEventListener('click', () => command('task.stop', '已向手机发送停止请求。'));
$('resume').addEventListener('click', () => command('task.resume', '手机已继续执行任务。'));
$('refresh').addEventListener('click', refreshRecords);
function clearModelConfig() {
  modelRequest += 1;
  modelConfig = null;
  ttsConfig = null;
  modelSaving = false;
  modelTesting = false;
  $('model-form').reset();
  $('model-language').querySelector('[data-custom]')?.remove();
  controls();
}
function dialogMessage(id, text, error = false) {
  $(id).textContent = text;
  $(id).classList.toggle('error', error);
}
const modelMessage = (text, error) => dialogMessage('model-message', text, error);
const appsMessage = (text, error) => dialogMessage('apps-message', text, error);
$('model-dialog').addEventListener('close', clearModelConfig);
$('model-form').addEventListener('input', controls);
$('model-open').addEventListener('click', async () => {
  clearModelConfig();
  const current = modelRequest;
  modelMessage('正在读取手机配置…');
  $('model-dialog').showModal();
  try {
    const [config, tts] = await Promise.all(['config.get', 'tts.get'].map(method => invoke('request', { method, params: {} })));
    if (current !== modelRequest || !$('model-dialog').open) return;
    modelConfig = config;
    ttsConfig = tts;
    $('jev-endpoint').value = config.jevEndpoint;
    $('jev-model').value = config.jevModel;
    $('jev-vision').checked = config.jevVision === true;
    $('chat-endpoint').value = config.chatEndpoint;
    $('chat-model').value = config.chatModel;
    for (const [id, configured] of [['jev-key', config.hasJevKey], ['chat-key', config.hasChatKey]]) {
      $(id).value = '';
      $(id).placeholder = configured ? '已设置，留空保留' : id === 'jev-key' ? '留空不启用 Jev' : '尚未设置，请填写';
    }
    if (![...$('model-language').options].some(option => option.value === config.language)) {
      const option = element('option', config.language);
      option.value = config.language;
      option.dataset.custom = '';
      $('model-language').append(option);
    }
    $('model-language').value = config.language;
    $('tts-endpoint').value = tts.endpoint;
    $('tts-token').placeholder = tts.hasToken ? '已设置，留空保留' : '未设置，使用本地播报';
    $('tts-model').value = tts.model;
    $('tts-voice').value = tts.voice;
    modelMessage('');
  } catch (error) { if (current === modelRequest) modelMessage(`读取失败：${message(error)}`, true); }
  finally { if (current === modelRequest) controls(); }
});
const modelParams = () => ({
  jevEndpoint: $('jev-endpoint').value.trim(), jevModel: $('jev-model').value.trim(), jevKey: $('jev-key').value.trim() || ($('jev-endpoint').value.trim() && modelConfig?.hasJevKey ? null : ''),
  chatEndpoint: $('chat-endpoint').value.trim(), chatModel: $('chat-model').value.trim(), chatKey: $('chat-key').value.trim() || null,
  language: $('model-language').value, jevVision: $('jev-vision').checked,
});
$('model-test').addEventListener('click', async () => {
  if ($('model-test').disabled || ![...$('model-fields').elements].every(field => field.reportValidity())) return;
  const current = modelRequest;
  modelTesting = true;
  controls();
  modelMessage('手机正在测试已配置的模型服务，最长约 2 分钟…');
  try {
    await invoke('request', { method: 'config.test', params: modelParams() });
    if (current === modelRequest) modelMessage('连接正常：已配置的模型服务都有响应。配置尚未保存。');
  } catch (error) { if (current === modelRequest) modelMessage(message(error), true); }
  finally { if (current === modelRequest) { modelTesting = false; controls(); } }
});
$('model-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if ($('model-save').disabled) return;
  const current = modelRequest;
  const saveModel = !$('model-fields').disabled;
  const params = modelParams();
  const ttsParams = { endpoint: $('tts-endpoint').value.trim(), token: $('tts-token').value.trim() || null,
    model: $('tts-model').value.trim(), voice: $('tts-voice').value.trim() };
  modelSaving = true;
  controls();
  modelMessage('');
  try {
    if (saveModel) await invoke('request', { method: 'config.set', params });
    const tts = await invoke('request', { method: 'tts.set', params: ttsParams });
    if (current !== modelRequest) return;
    $('model-dialog').close();
    clearModelConfig();
    notice(`${saveModel ? '模型与语音设置已保存到手机，下一次任务生效' : '语音设置已保存到手机'}；${tts.hasToken ? '使用在线播报' : '使用本地播报'}。`);
  } catch (error) { if (current === modelRequest) modelMessage(`保存失败：${message(error)}`, true); }
  finally { if (current === modelRequest) { modelSaving = false; controls(); } }
});
function clearApps() {
  appsRequest += 1;
  appAccess = null;
  appsSaving = false;
  $('apps-form').reset();
  $('apps-list').replaceChildren();
  $('apps-count').textContent = '';
  controls();
}
function renderApps() {
  if (!appAccess) return;
  const term = $('apps-search').value.trim().toLowerCase();
  // Same filter as the phone's picker: search covers system components, otherwise they need the toggle.
  const visible = appAccess.apps.filter((app) => term
    ? app.label.toLowerCase().includes(term) || app.packageName.toLowerCase().includes(term)
    : app.launchable || $('apps-system').checked);
  $('apps-list').replaceChildren(...visible.map((app) => {
    const row = element('label', null, 'app-row');
    const box = element('input');
    box.type = 'checkbox';
    box.checked = appAccess.selected.has(app.packageName);
    box.dataset.package = app.packageName;
    box.addEventListener('change', () => {
      if (box.checked) appAccess.selected.add(app.packageName); else appAccess.selected.delete(app.packageName);
      appsCount();
    });
    const text = element('span');
    text.append(element('strong', app.label), element('small', app.launchable ? app.packageName : `${app.packageName} · 系统组件 · 无启动入口`));
    row.append(box, text);
    return row;
  }));
  if (!visible.length) $('apps-list').append(element('p', '没有匹配的应用或组件', 'muted'));
  appsCount();
}
function appsCount() {
  $('apps-count').textContent = `已选 ${appAccess.selected.size} 个`;
  $('apps-count').classList.toggle('error', !appAccess.selected.size);
  controls();
}
function selectVisible(selected) {
  for (const box of $('apps-list').querySelectorAll('input[type=checkbox]')) {
    if (selected) appAccess.selected.add(box.dataset.package); else appAccess.selected.delete(box.dataset.package);
  }
  renderApps();
}
$('apps-dialog').addEventListener('close', clearApps);
$('apps-search').addEventListener('input', renderApps);
$('apps-system').addEventListener('change', renderApps);
$('apps-all').addEventListener('click', () => selectVisible(true));
$('apps-none').addEventListener('click', () => selectVisible(false));
$('apps-open').addEventListener('click', async () => {
  clearApps();
  const current = appsRequest;
  appsMessage('正在读取手机上的应用…');
  $('apps-dialog').showModal();
  try {
    const access = await invoke('request', { method: 'apps.get', params: {} });
    if (current !== appsRequest || !$('apps-dialog').open) return;
    appAccess = { apps: access.apps, selected: new Set(access.selected) };
    appsMessage('');
    renderApps();
  } catch (error) { if (current === appsRequest) appsMessage(`读取失败：${message(error)}`, true); }
  finally { if (current === appsRequest) controls(); }
});
$('apps-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if ($('apps-save').disabled) return;
  const current = appsRequest;
  appsSaving = true;
  controls();
  appsMessage('');
  try {
    await invoke('request', { method: 'apps.set', params: { apps: [...appAccess.selected] } });
    if (current !== appsRequest) return;
    $('apps-dialog').close();
    clearApps();
    notice('应用范围已保存到手机，下一次任务生效。');
  } catch (error) { if (current === appsRequest) appsMessage(`保存失败：${message(error)}`, true); }
  finally { if (current === appsRequest) { appsSaving = false; controls(); } }
});
$('pair-open').addEventListener('click', () => {
  $('address').replaceChildren(...snapshot.addresses.map((address) => {
    const option = element('option', address); option.value = address; return option;
  }));
  $('generate').disabled = !snapshot.addresses.length;
  $('pair-error').textContent = snapshot.addresses.length ? '' : '请先连接局域网，再重新打开此窗口。';
  $('pair-dialog').showModal();
});
$('generate').addEventListener('click', async () => {
  clearPairing();
  const current = pairingRequest;
  $('generate').disabled = true;
  $('pair-error').textContent = '';
  try {
    const pairing = await invoke('create_pairing', { host: $('address').value });
    if (current !== pairingRequest || !$('pair-dialog').open || snapshot.device) return;
    $('pair-code').value = pairing.code;
    expiresAt = pairing.expiresAt;
    $('pair-code-block').hidden = false;
    updateExpiry();
    try {
      const image = await QRCode.toDataURL(pairing.code, { width: 560, margin: 4, errorCorrectionLevel: 'M' });
      if (current !== pairingRequest || expiresAt <= Date.now()) return;
      $('pair-qr').src = image;
      $('pair-qr').hidden = false;
    } catch {
      if (current === pairingRequest) $('pair-error').textContent = '二维码生成失败，请复制设备码到手机绑定。';
    }
  } catch (error) { if (current === pairingRequest) $('pair-error').textContent = message(error); }
  finally { if (current === pairingRequest) $('generate').disabled = !snapshot.addresses.length; }
});
function clearPairing() {
  pairingRequest += 1;
  expiresAt = 0;
  $('pair-code').value = '';
  $('pair-qr').hidden = true;
  $('pair-qr').removeAttribute('src');
  $('pair-code-block').hidden = true;
}
$('pair-dialog').addEventListener('close', clearPairing);
function updateExpiry() {
  if (!expiresAt) return;
  const remaining = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
  $('code-expiry').textContent = remaining ? `${Math.floor(remaining / 60)} 分 ${remaining % 60} 秒内有效` : '设备码已过期，请重新生成';
  $('copy-code').disabled = !remaining;
  if (!remaining) {
    $('pair-code').value = '';
    $('pair-qr').hidden = true;
    $('pair-qr').removeAttribute('src');
  }
}
setInterval(updateExpiry, 1000);
$('forget').addEventListener('click', () => $('forget-dialog').showModal());
$('confirm-forget').addEventListener('click', async () => {
  $('confirm-forget').disabled = true;
  try {
    await invoke('forget_device');
    $('forget-dialog').close();
    clearPairing();
    render(await invoke('get_snapshot'));
    notice('已解除绑定。重新连接需要生成新的设备码。');
  } catch (error) { $('forget-dialog').close(); notice(message(error), true); }
  finally { $('confirm-forget').disabled = false; }
});
document.querySelectorAll('.close-dialog').forEach((button) => button.addEventListener('click', () => button.closest('dialog').close()));
initUpdates();
if (isTauri()) poll();
else {
  $('pair-open').disabled = true;
  $('connection-status').textContent = '网页预览';
  notice('请通过 npm run desktop 启动 Tauri 桌面应用，启用手机连接。');
}
