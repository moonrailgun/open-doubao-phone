import { invoke, isTauri } from '@tauri-apps/api/core';
import QRCode from 'qrcode';
import { createElement, createIcons, Check, CircleAlert, Copy, Link2, Link2Off, ListTodo, Network, RefreshCw, Send, Smartphone, SmartphoneNfc, Square, X } from 'lucide';
import './style.css';

const $ = (id) => document.getElementById(id);
const icon = (node) => createElement(node, { class: 'lucide', 'aria-hidden': 'true', focusable: 'false' });
createIcons({ icons: { Link2, Link2Off, ListTodo, Network, RefreshCw, Send, Smartphone, SmartphoneNfc, Square, X }, attrs: { 'aria-hidden': 'true', focusable: 'false' } });
const labels = { IDLE: '等待任务', RUNNING: '正在执行', PAUSED: '已暂停', COMPLETED: '已完成', SUCCEEDED: '已完成', DONE: '已完成', FAILED: '执行失败', STOPPED: '已停止', CANCELLED: '已停止', INTERRUPTED: '已中断' };
let snapshot = { connected: false, device: null, state: null, addresses: [] };
let sending = false;
let stopping = false;
let refreshing = false;
let recordKey = '';
let session = 0;
let detailRequest = 0;
let expiresAt = 0;
let pairingRequest = 0;
let historyRecords = [];
let historyRange = '';
let modelConfig = null;
let modelSaving = false;
let modelRequest = 0;
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
  $('stop').disabled = !snapshot.connected || stopping;
  $('refresh').disabled = !snapshot.connected || refreshing;
  $('model-open').disabled = !snapshot.connected;
  $('model-fields').disabled = !snapshot.connected || !modelConfig || modelSaving || running || paused;
  $('model-save').disabled = $('model-fields').disabled;
  $('model-save').textContent = modelSaving ? '正在保存…' : running || paused ? '请先停止当前任务' : '保存到手机';
  if (modelConfig) {
    $('jev-key').required = !modelConfig.hasJevKey || $('jev-endpoint').value.trim() !== modelConfig.jevEndpoint;
    $('chat-key').required = !modelConfig.hasChatKey || $('chat-endpoint').value.trim() !== modelConfig.chatEndpoint;
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
    $('current-result').textContent = state.result || '';
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
  $('record-content').replaceChildren(element('p', '正在读取…', 'muted'));
  $('record-dialog').showModal();
  try {
    const record = await invoke('request', { method: 'records.get', params: { taskId } });
    if (current !== detailRequest || currentSession !== session) return;
    const result = element('div', record.result || '暂无结果', 'prose');
    const log = element('pre', (record.log || []).join('\n') || '暂无日志', 'log');
    $('record-content').replaceChildren(element('h3', record.goal), element('p', `${labels[record.status] || record.status} · ${date(record.time)}`, 'muted'), result, element('h2', '执行与访问日志'), log);
    addCopyButton(result, '复制任务结果');
    addCopyButton(log, '复制执行与访问日志');
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
$('stop').addEventListener('click', async () => {
  const taskId = snapshot.state?.taskId;
  if (!taskId || !snapshot.connected || stopping) return;
  stopping = true;
  controls();
  try {
    await invoke('request', { method: 'task.stop', params: { taskId } });
    notice('已向手机发送停止请求。');
    render(await invoke('get_snapshot'));
  } catch (error) { notice(message(error), true); }
  finally { stopping = false; controls(); }
});
$('refresh').addEventListener('click', refreshRecords);
function clearModelConfig() {
  modelRequest += 1;
  modelConfig = null;
  modelSaving = false;
  $('model-form').reset();
  $('model-language').querySelector('[data-custom]')?.remove();
  controls();
}
function modelMessage(text, error = false) {
  $('model-message').textContent = text;
  $('model-message').classList.toggle('error', error);
}
$('model-dialog').addEventListener('close', clearModelConfig);
$('model-form').addEventListener('input', controls);
$('model-open').addEventListener('click', async () => {
  clearModelConfig();
  const current = modelRequest;
  modelMessage('正在读取手机配置…');
  $('model-dialog').showModal();
  try {
    const config = await invoke('request', { method: 'config.get', params: {} });
    if (current !== modelRequest || !$('model-dialog').open) return;
    modelConfig = config;
    $('jev-endpoint').value = config.jevEndpoint;
    $('jev-model').value = config.jevModel;
    $('chat-endpoint').value = config.chatEndpoint;
    $('chat-model').value = config.chatModel;
    for (const [id, configured] of [['jev-key', config.hasJevKey], ['chat-key', config.hasChatKey]]) {
      $(id).value = '';
      $(id).placeholder = configured ? '已设置，留空保留' : '尚未设置，请填写';
    }
    if (![...$('model-language').options].some(option => option.value === config.language)) {
      const option = element('option', config.language);
      option.value = config.language;
      option.dataset.custom = '';
      $('model-language').append(option);
    }
    $('model-language').value = config.language;
    modelMessage('');
  } catch (error) { if (current === modelRequest) modelMessage(`读取失败：${message(error)}`, true); }
  finally { if (current === modelRequest) controls(); }
});
$('model-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if ($('model-save').disabled) return;
  const current = modelRequest;
  const params = {
    jevEndpoint: $('jev-endpoint').value.trim(), jevModel: $('jev-model').value.trim(), jevKey: $('jev-key').value.trim() || null,
    chatEndpoint: $('chat-endpoint').value.trim(), chatModel: $('chat-model').value.trim(), chatKey: $('chat-key').value.trim() || null,
    language: $('model-language').value,
  };
  modelSaving = true;
  controls();
  modelMessage('');
  try {
    await invoke('request', { method: 'config.set', params });
    if (current !== modelRequest) return;
    $('model-dialog').close();
    clearModelConfig();
    notice('模型配置已保存到手机，下一次任务生效。');
  } catch (error) { if (current === modelRequest) modelMessage(`保存失败：${message(error)}`, true); }
  finally { if (current === modelRequest) { modelSaving = false; controls(); } }
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
if (isTauri()) poll();
else {
  $('pair-open').disabled = true;
  $('connection-status').textContent = '网页预览';
  notice('请通过 npm run desktop 启动 Tauri 桌面应用，启用手机连接。');
}
