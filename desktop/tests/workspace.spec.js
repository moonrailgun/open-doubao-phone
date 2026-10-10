import { test, expect } from '@playwright/test';
import jsQR from 'jsqr';

async function bridge(page, connected = true, records = [{ id: 'record-1', goal: '整理备忘录', status: 'COMPLETED', time: Date.now() }]) {
  await page.addInitScript(({ connected, records }) => {
    window.isTauri = true;
    window.testCalls = [];
    window.testRecords = records;
    window.testConfig = { jevEndpoint: 'https://jev.example/v1', jevModel: 'jev', hasJevKey: true,
      chatEndpoint: 'https://chat.example/v1', chatModel: 'chat', hasChatKey: true, language: '' };
    window.testApps = { selected: ['com.example.notes'], apps: [
      { packageName: 'com.example.notes', label: '备忘录', launchable: true },
      { packageName: 'com.android.chrome', label: 'Chrome', launchable: true },
      { packageName: 'com.google.android.gms', label: 'Google Play 服务', launchable: false },
    ] };
    window.testTts = { endpoint: 'https://speech.example/v1/audio/speech', model: 'eleven', voice: 'voice-1', hasToken: true };
    window.testSnapshot = {
      listening: true, addresses: ['192.168.1.20'], port: 38687, connected,
      device: connected ? { id: 'phone-1', name: '测试手机' } : null,
      state: null, error: null,
    };
    window.__TAURI_INTERNALS__ = {
      invoke: async (command, args) => {
        window.testCalls.push({ command, args });
        if (command === 'get_snapshot') {
          if (window.testSnapshotFailure) throw '服务已关闭';
          return structuredClone(window.testSnapshot);
        }
        if (command === 'create_pairing') {
          if (window.testPairingFailure) throw window.testPairingFailure;
          if (window.testPairingPending) await new Promise(resolve => { window.finishPairing = resolve; });
          return { code: `odoubao://pair?v=1&host=192.168.1.20&port=38687&fingerprint=${'a'.repeat(64)}&token=${'b'.repeat(64)}`, expiresAt: Date.now() + 300000 };
        }
        if (command === 'forget_device') { window.testSnapshot.device = null; window.testSnapshot.connected = false; window.testSnapshot.state = null; return; }
        if (command === 'request') {
          if (window.testFailure) throw window.testFailure;
          if (args.method === 'config.get') {
            if (window.testConfigPending) await new Promise(resolve => { window.finishConfig = resolve; });
            return structuredClone(window.testConfig);
          }
          if (args.method === 'config.set') {
            if (window.testConfigFailure) throw window.testConfigFailure;
            return structuredClone(window.testConfig);
          }
          if (args.method === 'records.list') return structuredClone(window.testRecords);
          if (args.method === 'records.get') return { ...window.testRecords.find(record => record.id === args.params.taskId), result: '<img src=x onerror=alert(1)>', log: ['12:00 读取 com.example.notes', '12:01 完成'] };
          if (args.method === 'task.start') {
            window.testSnapshot.state = { taskId: 'task-1', status: 'RUNNING', goal: args.params.goal, message: '正在读取', steps: 1, result: '', log: ['读取应用'] };
            return { taskId: 'task-1' };
          }
          if (args.method === 'task.stop') { window.testSnapshot.state.status = 'STOPPED'; return { taskId: args.params.taskId }; }
          if (args.method === 'task.resume') { window.testSnapshot.state.status = 'RUNNING'; return { taskId: args.params.taskId }; }
          if (args.method === 'records.delete') { window.testRecords = window.testRecords.filter(record => record.id !== args.params.taskId); return { taskId: args.params.taskId }; }
          if (args.method === 'config.test') { if (window.testConnectionFailure) throw window.testConnectionFailure; return {}; }
          if (args.method === 'apps.get') return structuredClone(window.testApps);
          if (args.method === 'apps.set') { window.testApps.selected = args.params.apps; return { selected: args.params.apps.length }; }
          if (args.method === 'tts.get') {
            if (window.testTtsPending) await new Promise(resolve => { window.finishTts = resolve; });
            return structuredClone(window.testTts);
          }
          if (args.method === 'tts.set') {
            if (window.testTtsFailure) throw window.testTtsFailure;
            const { endpoint, token, model, voice } = args.params;
            return { endpoint, model, voice, hasToken: Boolean(token) || (endpoint !== '' && window.testTts.hasToken) };
          }
        }
        throw new Error(`Unexpected command: ${command}`);
      },
    };
  }, { connected, records });
}

const recentRecords = Array.from({ length: 30 }, (_, index) => ({
  id: `history-${index + 1}`, goal: `历史任务 ${index + 1}`, status: 'COMPLETED', time: 1791504000000 - index * 60000,
}));

async function expectNoDocumentOverflow(page) {
  expect(await page.evaluate(() => ({
    horizontal: document.documentElement.scrollWidth > innerWidth,
    vertical: document.documentElement.scrollHeight > innerHeight,
  }))).toEqual({ horizontal: false, vertical: false });
}

async function clipboard(page, { failWrite = false, fallbackWorks = false } = {}) {
  await page.addInitScript(({ failWrite, fallbackWorks }) => {
    window.testClipboardWrites = [];
    window.testFallbackCopies = [];
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async (text) => {
        window.testClipboardWrites.push(text);
        if (failWrite) throw new DOMException('Clipboard access denied', 'NotAllowedError');
      } },
    });
    document.execCommand = (command) => {
      if (command !== 'copy') return false;
      const selected = document.activeElement;
      window.testFallbackCopies.push(typeof selected?.value === 'string'
        ? selected.value.slice(selected.selectionStart, selected.selectionEnd)
        : window.getSelection().toString());
      return fallbackWorks;
    };
  }, { failWrite, fallbackWorks });
}

test('paired phone receives a task and stop is scoped to its task ID', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await expect(page.getByText('测试手机', { exact: true })).toBeVisible();
  await page.getByLabel('这次需要做什么？').fill('整理今天的待办');
  await page.getByRole('button', { name: '下发任务' }).click();
  await expect(page.locator('#current-goal')).toHaveText('整理今天的待办');
  await expect(page.getByRole('button', { name: '下发任务' })).toBeDisabled();
  await page.getByRole('button', { name: '停止任务' }).click();
  await expect(page.locator('#task-badge')).toHaveText('已停止');
  const stops = await page.evaluate(() => window.testCalls.filter(c => c.args?.method === 'task.stop'));
  expect(stops).toHaveLength(1);
  expect(stops[0].args.params).toEqual({ taskId: 'task-1' });
});

test('paused tasks keep stop available and block a new dispatch until released', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.getByLabel('这次需要做什么？').fill('下一条任务');
  await page.evaluate(() => {
    window.testSnapshot.state = { taskId: 'paused-1', status: 'PAUSED', goal: '打开日历', message: '请先处理登录', steps: 1, result: '', log: [] };
  });
  await expect(page.locator('#task-badge')).toHaveText('已暂停');
  await expect(page.locator('#current-status')).toHaveText('已暂停');
  await expect(page.locator('#compose-hint')).toContainText('在手机处理后点击继续');
  await expect(page.getByRole('button', { name: '下发任务' })).toBeDisabled();
  await page.getByRole('button', { name: '停止任务' }).click();
  await expect(page.locator('#task-badge')).toHaveText('已停止');
  await expect(page.getByRole('button', { name: '下发任务' })).toBeEnabled();
  const stops = await page.evaluate(() => window.testCalls.filter(c => c.args?.method === 'task.stop'));
  expect(stops).toHaveLength(1);
  expect(stops[0].args.params).toEqual({ taskId: 'paused-1' });
  expect(await page.evaluate(() => window.testCalls.filter(c => c.args?.method === 'task.start'))).toHaveLength(0);
});

test('device code and unbinding have explicit controls and clear credentials', async ({ page }) => {
  await bridge(page, false);
  await page.goto('/');
  await page.getByRole('button', { name: '绑定手机', exact: true }).click();
  await page.getByRole('button', { name: '生成设备码' }).click();
  await expect(page.getByLabel('设备码', { exact: true })).toHaveValue(/odoubao:\/\/pair/);
  await page.evaluate(() => { window.testSnapshot.device = { id: 'phone-1', name: '测试手机' }; window.testSnapshot.connected = true; });
  await expect(page.locator('#pair-dialog')).not.toBeVisible();
  await expect(page.locator('#pair-code')).toHaveValue('');
  await expect(page.locator('#pair-qr')).not.toHaveAttribute('src');
  await page.getByRole('button', { name: '解除绑定', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '解除绑定', exact: true }).click();
  await expect(page.locator('#device-name')).toHaveText('尚未绑定设备');
  await expect(page.getByRole('button', { name: '下发任务' })).toBeDisabled();
});

test('pairing QR decodes to the complete device code and expires with it', async ({ page }, testInfo) => {
  await bridge(page, false);
  await page.clock.install();
  await page.goto('/');
  await page.getByRole('button', { name: '绑定手机', exact: true }).click();
  await page.getByRole('button', { name: '生成设备码' }).click();
  const qr = page.getByRole('img', { name: '手机绑定二维码' });
  await expect(qr).toBeVisible();
  const pixels = await qr.evaluate(async img => {
    await img.decode();
    const canvas = document.createElement('canvas');
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    const context = canvas.getContext('2d');
    context.drawImage(img, 0, 0);
    return { data: [...context.getImageData(0, 0, canvas.width, canvas.height).data], width: canvas.width, height: canvas.height };
  });
  expect(jsQR(new Uint8ClampedArray(pixels.data), pixels.width, pixels.height)?.data).toBe(await page.locator('#pair-code').inputValue());
  await page.screenshot({ path: testInfo.outputPath('pairing-qr.png'), fullPage: true });
  await page.clock.fastForward(300001);
  await expect(qr).not.toBeVisible();
  await expect(page.locator('#pair-qr')).not.toHaveAttribute('src');
  await expect(page.locator('#pair-code')).toHaveValue('');
  await expect(page.locator('#code-expiry')).toHaveText('设备码已过期，请重新生成');
  await expect(page.getByRole('button', { name: '复制设备码' })).toBeDisabled();
  await page.getByRole('button', { name: '生成设备码' }).click();
  await expect(qr).toBeVisible();
  await expect(page.getByRole('button', { name: '复制设备码' })).toBeEnabled();
});

test('regeneration failure and closing clear the previous pairing QR', async ({ page }) => {
  await bridge(page, false);
  await page.goto('/');
  await page.getByRole('button', { name: '绑定手机', exact: true }).click();
  await page.getByRole('button', { name: '生成设备码' }).click();
  await expect(page.locator('#pair-qr')).toBeVisible();
  await page.evaluate(() => { window.testPairingFailure = '生成失败'; });
  await page.getByRole('button', { name: '生成设备码' }).click();
  await expect(page.locator('#pair-error')).toHaveText('生成失败');
  await expect(page.locator('#pair-qr')).not.toHaveAttribute('src');
  await expect(page.locator('#pair-code')).toHaveValue('');
  await page.evaluate(() => { window.testPairingFailure = null; });
  await page.getByRole('button', { name: '生成设备码' }).click();
  await expect(page.locator('#pair-qr')).toBeVisible();
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await expect(page.locator('#pair-qr')).not.toHaveAttribute('src');
  await expect(page.locator('#pair-code')).toHaveValue('');
});

test('pairing response arriving after closing is discarded', async ({ page }) => {
  await bridge(page, false);
  await page.goto('/');
  await page.evaluate(() => { window.testPairingPending = true; });
  await page.getByRole('button', { name: '绑定手机', exact: true }).click();
  await page.getByRole('button', { name: '生成设备码' }).click();
  await expect.poll(() => page.evaluate(() => typeof window.finishPairing)).toBe('function');
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await page.evaluate(() => window.finishPairing());
  await page.getByRole('button', { name: '绑定手机', exact: true }).click();
  await expect(page.locator('#pair-code-block')).not.toBeVisible();
  await expect(page.locator('#pair-code')).toHaveValue('');
});

test('history renders untrusted output as text and shows access logs', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.getByRole('button', { name: /整理备忘录/ }).click();
  await expect(page.locator('#record-content')).toContainText('<img src=x onerror=alert(1)>');
  await expect(page.locator('#record-content img')).toHaveCount(0);
  await expect(page.locator('#record-content')).toContainText('读取 com.example.notes');
});

test('history virtualizes 30 records and clears old rows after refresh and disconnect', async ({ page }, testInfo) => {
  await bridge(page, true, recentRecords);
  await page.goto('/');
  const records = page.locator('#records');
  const rows = records.locator('.record-row');
  await expect(rows.first()).toContainText('历史任务 1');
  expect(await rows.count()).toBeLessThan(recentRecords.length);
  expect(await records.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
  await expectNoDocumentOverflow(page);
  await page.screenshot({ path: testInfo.outputPath('history-initial.png'), fullPage: true });

  await rows.first().focus();
  for (let index = 1; index <= recentRecords.length; index += 1) {
    if (index > 1) await page.keyboard.press('Tab');
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await expect(records.locator('.record-row:focus .record-goal')).toHaveText(`历史任务 ${index}`);
  }
  expect(await records.evaluate(el => el.scrollTop)).toBeGreaterThan(0);
  await records.evaluate(el => { el.scrollTop = el.scrollHeight; });
  const lastRecord = records.getByRole('button', { name: /^历史任务 30/ });
  await expect(lastRecord).toBeVisible();
  await expect(records.getByRole('button', { name: /^历史任务 1\s/ })).toHaveCount(0);
  expect(await rows.count()).toBeLessThan(recentRecords.length);
  await expectNoDocumentOverflow(page);
  await page.screenshot({ path: testInfo.outputPath('history-scrolled.png'), fullPage: true });
  await lastRecord.click();
  await expect(page.locator('#record-content h3')).toHaveText('历史任务 30');
  expect(await page.evaluate(() => window.testCalls.filter(call => call.args?.method === 'records.get').at(-1).args.params)).toEqual({ taskId: 'history-30' });
  await page.locator('#record-dialog').getByRole('button', { name: '关闭', exact: true }).click();

  await page.evaluate(() => { window.testRecords = window.testRecords.slice(0, 2).map(record => ({ ...record, goal: `刷新后的${record.goal}` })); });
  await page.getByRole('button', { name: '刷新记录', exact: true }).click();
  await expect(rows).toHaveCount(2);
  await expect(rows.first()).toContainText('刷新后的历史任务 1');
  await expect(records).not.toContainText('历史任务 30');
  expect(await records.evaluate(el => el.scrollTop)).toBe(0);

  await page.evaluate(() => { window.testSnapshot.connected = false; });
  await expect(rows).toHaveCount(0);
  await expect(records).toContainText('连接手机后读取任务记录');
});

test('short and narrow windows keep task and history scrolling inside the workspace', async ({ page }, testInfo) => {
  await bridge(page, true, recentRecords);
  await page.goto('/');
  await page.evaluate(() => {
    window.testSnapshot.state = {
      taskId: 'long-result', status: 'COMPLETED', goal: '整理完整的工作记录', steps: 30,
      result: Array.from({ length: 40 }, (_, index) => `第 ${index + 1} 项：核对今天的任务与执行结果。`).join('\n'),
      log: ['已完成全部任务'],
    };
  });
  await expect(page.locator('#current-result')).toContainText('第 40 项');
  for (const viewport of [{ width: 1280, height: 560 }, { width: 900, height: 720 }, { width: 420, height: 850 }]) {
    await page.setViewportSize(viewport);
    const task = page.locator('.task-column');
    await expectNoDocumentOverflow(page);
    expect(await task.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
    await task.evaluate(el => { el.scrollTop = el.scrollHeight; });
    expect(await task.evaluate(el => el.scrollTop)).toBeGreaterThan(0);
    const records = page.locator('#records');
    expect(await records.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
    expect(await records.locator('.record-row').count()).toBeLessThan(recentRecords.length);
    await page.screenshot({ path: testInfo.outputPath(`workspace-${viewport.width}x${viewport.height}.png`), fullPage: true });
  }
});

test('copy buttons preserve complete multiline live and history logs', async ({ page }, testInfo) => {
  await bridge(page);
  await clipboard(page);
  await page.goto('/');
  const log = ['16:20:00 打开 com.example.notes', '  读取 <页面> 与中文内容', '', '16:20:04 生成总结'];
  await page.evaluate((log) => {
    window.testSnapshot.state = { taskId: 'copy-1', status: 'COMPLETED', goal: '整理备忘录', result: '完成', log };
  }, log);
  await expect(page.locator('#live-log')).toHaveJSProperty('textContent', log.join('\n'));
  await page.locator('#live-details > summary').click();
  const liveCopy = page.getByRole('button', { name: '复制执行日志', exact: true });
  await expect(liveCopy.locator('svg[aria-hidden="true"]')).toBeVisible();
  await expect(liveCopy.getByRole('status')).toHaveClass('sr-only');
  await liveCopy.click();
  await expect(liveCopy).toHaveAttribute('title', '已复制完整内容');
  await expect(liveCopy.getByRole('status')).toHaveText('已复制');
  await page.getByRole('button', { name: /整理备忘录/ }).click();
  const historyCopy = page.getByRole('button', { name: '复制执行与访问日志', exact: true });
  await historyCopy.click();
  await expect(historyCopy).toHaveAttribute('title', '已复制完整内容');
  expect(await page.evaluate(() => window.testClipboardWrites)).toEqual([
    log.join('\n'), '12:00 读取 com.example.notes\n12:01 完成',
  ]);
  expect(await page.evaluate(() => window.testFallbackCopies)).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath('record-copy.png'), fullPage: true });
});

test('history log copying falls back when Clipboard API access is denied', async ({ page }) => {
  await bridge(page);
  await clipboard(page, { failWrite: true, fallbackWorks: true });
  await page.goto('/');
  await page.getByRole('button', { name: /整理备忘录/ }).click();
  const copy = page.getByRole('button', { name: '复制执行与访问日志', exact: true });
  await copy.click();
  await expect(copy).toHaveAttribute('title', '已复制完整内容');
  const expected = ['12:00 读取 com.example.notes\n12:01 完成'];
  expect(await page.evaluate(() => window.testClipboardWrites)).toEqual(expected);
  expect(await page.evaluate(() => window.testFallbackCopies)).toEqual(expected);
});

test('copy failure is visible and leaves the complete history log available', async ({ page }) => {
  await bridge(page);
  await clipboard(page, { failWrite: true });
  await page.goto('/');
  await page.getByRole('button', { name: /整理备忘录/ }).click();
  const copy = page.getByRole('button', { name: '复制执行与访问日志', exact: true });
  await copy.click();
  await expect(copy).toHaveAttribute('title', '复制失败，请选中文字后使用系统复制快捷键。');
  await expect(copy.getByRole('status')).toHaveText('复制失败');
  await expect(page.locator('#record-content pre')).toHaveJSProperty('textContent', '12:00 读取 com.example.notes\n12:01 完成');
  expect(await page.evaluate(() => window.testClipboardWrites)).toHaveLength(1);
  expect(await page.evaluate(() => window.testFallbackCopies)).toHaveLength(1);
});

test('a failed dispatch keeps the draft and never retries automatically', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.evaluate(() => { window.testFailure = '响应超时，执行状态未知，请查看手机'; });
  await page.getByLabel('这次需要做什么？').fill('保留这条任务');
  await page.getByRole('button', { name: '下发任务' }).click();
  await expect(page.locator('#notice')).toContainText('执行状态未知');
  await expect(page.getByLabel('这次需要做什么？')).toHaveValue('保留这条任务');
  await page.waitForTimeout(1400);
  expect(await page.evaluate(() => window.testCalls.filter(c => c.args?.method === 'task.start').length)).toBe(1);
});

test('completed tasks expose summaries and live access logs', async ({ page }, testInfo) => {
  await bridge(page);
  await page.goto('/');
  await page.evaluate(() => {
    window.testSnapshot.state = { taskId: 'finished-1', status: 'COMPLETED', goal: '整理今天的待办', message: '任务已完成', steps: 6, result: '今天有三项待办：确认需求、整理文档、检查构建。', log: ['16:20:00 打开备忘录 com.example.notes', '16:20:01 读取页面：发现 8 个元素', '16:20:04 生成任务总结'] };
  });
  await expect(page.locator('#current-result')).toContainText('今天有三项待办');
  await expect(page.locator('#live-log')).toContainText('com.example.notes');
  await expect(page.locator('#task-badge')).toHaveText('已完成');
  await page.screenshot({ path: testInfo.outputPath('workspace.png'), fullPage: true });
});

test('losing the native service disables dispatch and closes stale details', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.getByLabel('这次需要做什么？').fill('稍后执行');
  await expect(page.getByRole('button', { name: '下发任务' })).toBeEnabled();
  await page.getByRole('button', { name: /整理备忘录/ }).click();
  await expect(page.locator('#record-content')).toContainText('com.example.notes');
  await page.evaluate(() => { window.testSnapshotFailure = true; });
  await expect(page.locator('#notice')).toContainText('服务已关闭');
  await expect(page.getByRole('button', { name: '下发任务' })).toBeDisabled();
  await expect(page.locator('#record-dialog')).not.toBeVisible();
});

test('browser preview explains native runtime and has no horizontal overflow', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#notice')).toContainText('Tauri 桌面应用');
  await expect(page.getByRole('button', { name: '绑定手机', exact: true })).toBeDisabled();
  for (const width of [1280, 900, 420]) {
    await page.setViewportSize({ width, height: 850 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
});

test('model settings preserve hidden keys and send replacements only when entered', async ({ page }, testInfo) => {
  await bridge(page);
  await page.goto('/');
  await page.getByRole('button', { name: '模型配置', exact: true }).click();
  await expect(page.getByLabel('Jev 接口地址', { exact: true })).toHaveValue('https://jev.example/v1');
  await expect(page.getByLabel('Jev API Key', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('Jev API Key', { exact: true })).toHaveAttribute('placeholder', '已设置，留空保留');
  await page.getByLabel('Jev 模型名称', { exact: true }).fill('new-jev');
  await page.getByLabel('通用模型接口地址', { exact: true }).fill('https://new.example/v1');
  await expect(page.getByLabel('通用模型 API Key', { exact: true })).toHaveAttribute('required', '');
  await page.getByLabel('通用模型 API Key', { exact: true }).fill('replacement-secret');
  await page.getByLabel('总结语言', { exact: true }).selectOption('zh-CN');
  await page.screenshot({ path: testInfo.outputPath('model-settings.png'), fullPage: true });
  await page.getByRole('button', { name: '保存到手机', exact: true }).click();
  await expect(page.locator('#model-dialog')).not.toBeVisible();
  await expect(page.locator('#notice')).toContainText('模型配置已保存到手机');
  expect(await page.evaluate(() => window.testCalls.find(call => call.args?.method === 'config.set').args.params)).toEqual({
    jevEndpoint: 'https://jev.example/v1', jevModel: 'new-jev', jevKey: null,
    chatEndpoint: 'https://new.example/v1', chatModel: 'chat', chatKey: 'replacement-secret', language: 'zh-CN',
  });
  await expect(page.locator('#chat-key')).toHaveValue('');
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
});

test('model save failure stays editable, busy tasks block saving, and disconnect clears secrets', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.getByRole('button', { name: '模型配置', exact: true }).click();
  await expect(page.locator('#model-save')).toBeEnabled();
  await page.getByLabel('Jev API Key', { exact: true }).fill('draft-secret');
  await page.evaluate(() => { window.testConfigFailure = '手机保存失败'; });
  await page.getByRole('button', { name: '保存到手机', exact: true }).click();
  await expect(page.locator('#model-message')).toContainText('手机保存失败');
  await expect(page.locator('#model-save')).toBeEnabled();
  await page.evaluate(() => { window.testSnapshot.state = { status: 'PAUSED' }; });
  await expect(page.locator('#model-save')).toBeDisabled();
  await page.evaluate(() => { window.testSnapshot.connected = false; });
  await expect(page.locator('#model-dialog')).not.toBeVisible();
  await expect(page.locator('#jev-key')).toHaveValue('');
  await expect(page.locator('#model-open')).toBeDisabled();
});

test('a model response arriving after closing is ignored and unset keys are required', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.evaluate(() => { window.testConfigPending = true; });
  await page.getByRole('button', { name: '模型配置', exact: true }).click();
  await expect.poll(() => page.evaluate(() => typeof window.finishConfig)).toBe('function');
  await page.locator('#model-dialog').getByRole('button', { name: '关闭', exact: true }).click();
  await page.evaluate(() => window.finishConfig());
  await expect(page.locator('#jev-endpoint')).toHaveValue('');
  await page.evaluate(() => { window.testConfigPending = false; window.testConfig.hasJevKey = false; });
  await page.getByRole('button', { name: '模型配置', exact: true }).click();
  await expect(page.locator('#jev-key')).toHaveAttribute('required', '');
  await expect(page.locator('#jev-key')).toHaveAttribute('placeholder', '尚未设置，请填写');
});

test('a paused task can be resumed from the desktop by its task ID', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.evaluate(() => {
    window.testSnapshot.state = { taskId: 'task-1', status: 'RUNNING', goal: '打开日历', message: '正在读取', steps: 1, result: '', log: [] };
  });
  await expect(page.getByRole('button', { name: '停止任务' })).toBeVisible();
  await expect(page.getByRole('button', { name: '继续任务' })).toBeHidden();
  await page.evaluate(() => { window.testSnapshot.state.status = 'PAUSED'; });
  await page.getByRole('button', { name: '继续任务' }).click();
  await expect(page.locator('#task-badge')).toHaveText('正在执行');
  await expect(page.getByRole('button', { name: '继续任务' })).toBeHidden();
  const resumes = await page.evaluate(() => window.testCalls.filter(c => c.args?.method === 'task.resume'));
  expect(resumes.map(c => c.args.params)).toEqual([{ taskId: 'task-1' }]);
});

test('app scope mirrors the phone picker and saves the selection', async ({ page }, testInfo) => {
  await bridge(page);
  await page.goto('/');
  await page.getByRole('button', { name: '应用范围', exact: true }).click();
  const list = page.locator('#apps-list');
  await expect(list.getByLabel(/备忘录/)).toBeChecked();
  await expect(list.getByLabel(/Chrome/)).not.toBeChecked();
  await expect(list.getByText('Google Play 服务')).toHaveCount(0);
  await page.getByLabel(/显示系统组件/).check();
  await expect(list.getByText('系统组件 · 无启动入口', { exact: false })).toBeVisible();
  await page.getByLabel(/显示系统组件/).uncheck();
  await page.getByLabel('搜索应用名称或包名').fill('gms');
  await expect(list.getByLabel(/Google Play 服务/)).toBeVisible();
  await page.getByRole('button', { name: '全选当前列表' }).click();
  await page.getByLabel('搜索应用名称或包名').fill('');
  await page.getByRole('button', { name: '取消当前列表' }).click();
  await expect(page.locator('#apps-count')).toHaveText('已选 1 个');
  await expect(page.locator('#apps-save')).toBeEnabled();
  await page.getByLabel('搜索应用名称或包名').fill('gms');
  await list.getByLabel(/Google Play 服务/).uncheck();
  await expect(page.locator('#apps-count')).toHaveText('已选 0 个');
  await expect(page.locator('#apps-save')).toBeDisabled();
  await page.getByLabel('搜索应用名称或包名').fill('');
  await list.getByLabel(/Chrome/).check();
  await page.screenshot({ path: testInfo.outputPath('app-scope.png'), fullPage: true });
  await page.getByRole('button', { name: '保存到手机', exact: true }).click();
  await expect(page.locator('#apps-dialog')).not.toBeVisible();
  await expect(page.locator('#notice')).toContainText('应用范围已保存到手机');
  expect(await page.evaluate(() => window.testCalls.find(c => c.args?.method === 'apps.set').args.params))
    .toEqual({ apps: ['com.android.chrome'] });
});

test('voice settings keep the hidden token and require a new one for a new endpoint', async ({ page }, testInfo) => {
  await bridge(page);
  await page.goto('/');
  await page.getByRole('button', { name: '语音播报', exact: true }).click();
  const endpoint = page.getByLabel('在线播报接口地址');
  const token = page.getByLabel('在线播报 Token');
  await expect(endpoint).toHaveValue('https://speech.example/v1/audio/speech');
  await expect(token).toHaveValue('');
  await expect(token).toHaveAttribute('placeholder', '已设置，留空保留');
  await endpoint.fill('https://other.example/v1/audio/speech');
  await expect(token).toHaveAttribute('required', '');
  await endpoint.fill('');
  await expect(token).not.toHaveAttribute('required');
  await page.getByLabel('音色 ID').fill(' voice-2 ');
  await page.screenshot({ path: testInfo.outputPath('voice-settings.png'), fullPage: true });
  await page.getByRole('button', { name: '保存到手机', exact: true }).click();
  await expect(page.locator('#tts-dialog')).not.toBeVisible();
  await expect(page.locator('#notice')).toContainText('使用本地播报');
  expect(await page.evaluate(() => window.testCalls.find(c => c.args?.method === 'tts.set').args.params))
    .toEqual({ endpoint: '', token: null, model: 'eleven', voice: 'voice-2' });
});

test('voice settings save a new online token while a task runs', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.evaluate(() => { window.testSnapshot.state = { taskId: 'task-1', status: 'RUNNING', goal: 'x', message: '', steps: 1, result: '', log: [] }; });
  await expect(page.locator('#task-badge')).toHaveText('正在执行');
  await page.getByRole('button', { name: '语音播报', exact: true }).click();
  await expect(page.locator('#tts-save')).toHaveText('保存到手机');
  await expect(page.locator('#tts-save')).toBeEnabled();
  await page.getByLabel('在线播报接口地址').fill(' https://other.example/v1/audio/speech ');
  await page.getByLabel('在线播报 Token').fill(' new-token ');
  await page.getByLabel('播报模型').fill('flash');
  await page.getByRole('button', { name: '保存到手机', exact: true }).click();
  await expect(page.locator('#tts-dialog')).not.toBeVisible();
  await expect(page.locator('#notice')).toContainText('在线播报设置已保存到手机');
  await expect(page.locator('#tts-token')).toHaveValue('');
  expect(await page.evaluate(() => window.testCalls.find(c => c.args?.method === 'tts.set').args.params))
    .toEqual({ endpoint: 'https://other.example/v1/audio/speech', token: 'new-token', model: 'flash', voice: 'voice-1' });
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
});

test('voice read and save failures stay visible, keep the draft, and disconnect clears the token', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.evaluate(() => { window.testFailure = '手机未响应'; });
  await page.getByRole('button', { name: '语音播报', exact: true }).click();
  await expect(page.locator('#tts-message')).toHaveClass(/error/);
  await expect(page.locator('#tts-message')).toContainText('读取失败：手机未响应');
  await expect(page.locator('#tts-endpoint')).toBeDisabled();
  await expect(page.locator('#tts-save')).toBeDisabled();
  await page.locator('#tts-dialog').getByRole('button', { name: '关闭', exact: true }).click();
  await page.evaluate(() => { window.testFailure = null; window.testTtsFailure = '语音设置无效'; });
  await page.getByRole('button', { name: '语音播报', exact: true }).click();
  await expect(page.locator('#tts-message')).toHaveText('');
  await page.getByLabel('在线播报 Token').fill('draft-token');
  await page.getByLabel('音色 ID').fill('voice-2');
  await page.getByRole('button', { name: '保存到手机', exact: true }).click();
  await expect(page.locator('#tts-message')).toContainText('保存失败：语音设置无效');
  await expect(page.locator('#tts-save')).toBeEnabled();
  await expect(page.locator('#tts-token')).toHaveValue('draft-token');
  await expect(page.locator('#tts-voice')).toHaveValue('voice-2');
  await page.evaluate(() => { window.testSnapshot.connected = false; });
  await expect(page.locator('#tts-dialog')).not.toBeVisible();
  await expect(page.locator('#tts-token')).toHaveValue('');
  await expect(page.getByRole('button', { name: '语音播报', exact: true })).toBeDisabled();
});

test('a voice response arriving after closing is ignored and an unset token stays optional', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.evaluate(() => { window.testTtsPending = true; });
  await page.getByRole('button', { name: '语音播报', exact: true }).click();
  await expect.poll(() => page.evaluate(() => typeof window.finishTts)).toBe('function');
  await page.locator('#tts-dialog').getByRole('button', { name: '关闭', exact: true }).click();
  await page.evaluate(() => { window.finishTts(); return new Promise(resolve => setTimeout(resolve)); });
  await expect(page.locator('#tts-endpoint')).toHaveValue('');
  await expect(page.locator('#tts-token')).not.toHaveAttribute('placeholder');
  await page.evaluate(() => { window.testTtsPending = false; window.testTts.hasToken = false; });
  await page.getByRole('button', { name: '语音播报', exact: true }).click();
  await expect(page.locator('#tts-token')).toHaveAttribute('placeholder', '未设置，使用本地播报');
  await page.getByLabel('在线播报接口地址').fill('https://other.example/v1/audio/speech');
  await expect(page.locator('#tts-token')).not.toHaveAttribute('required');
});

test('app scope cannot change during a task and closes on disconnect', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.getByRole('button', { name: '应用范围', exact: true }).click();
  await expect(page.locator('#apps-save')).toBeEnabled();
  await page.evaluate(() => { window.testSnapshot.state = { taskId: 'task-1', status: 'RUNNING', goal: 'x', message: '', steps: 1, result: '', log: [] }; });
  await expect(page.locator('#apps-save')).toHaveText('请先停止当前任务');
  await expect(page.locator('#apps-save')).toBeDisabled();
  await expect(page.locator('#apps-list input').first()).toBeDisabled();
  await page.evaluate(() => { window.testSnapshot.connected = false; });
  await expect(page.locator('#apps-dialog')).not.toBeVisible();
  await expect(page.getByRole('button', { name: '应用范围', exact: true })).toBeDisabled();
});

test('connection test sends unsaved form values and never saves them', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.getByRole('button', { name: '模型配置', exact: true }).click();
  await page.getByLabel('通用模型名称', { exact: true }).fill('draft-model');
  await page.getByRole('button', { name: '测试连接' }).click();
  await expect(page.locator('#model-message')).toContainText('连接正常');
  expect(await page.evaluate(() => window.testCalls.find(c => c.args?.method === 'config.test').args.params)).toEqual({
    jevEndpoint: 'https://jev.example/v1', jevModel: 'jev', jevKey: null,
    chatEndpoint: 'https://chat.example/v1', chatModel: 'draft-model', chatKey: null, language: '',
  });
  await page.evaluate(() => { window.testConnectionFailure = 'Jev 连接测试失败\nPOST https://jev.example/v1'; });
  await page.getByRole('button', { name: '测试连接' }).click();
  await expect(page.locator('#model-message')).toHaveClass(/error/);
  await expect(page.locator('#model-message')).toContainText('Jev 连接测试失败');
  await page.getByLabel('通用模型接口地址', { exact: true }).fill('https://other.example/v1');
  await page.getByRole('button', { name: '测试连接' }).click();
  expect(await page.evaluate(() => window.testCalls.filter(c => c.args?.method === 'config.test').length)).toBe(2);
  expect(await page.evaluate(() => window.testCalls.filter(c => c.args?.method === 'config.set'))).toHaveLength(0);
});

test('summaries render the phone Markdown subset as text nodes', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.evaluate(() => {
    window.testSnapshot.state = { taskId: 'finished-1', status: 'COMPLETED', goal: '对比', message: '', steps: 3,
      result: '## 结论\n- **iPhone 18** 更值得买\n- 价格 `5999` 元，*仅供参考*\n---\n<b>原样</b> 2 * 3 * 4', log: [] };
  });
  const result = page.locator('#current-result');
  await expect(result.locator('.md-heading')).toHaveText('结论');
  await expect(result.locator('strong', { hasText: 'iPhone 18' })).toBeVisible();
  await expect(result.locator('code')).toHaveText('5999');
  await expect(result.locator('em')).toHaveText('仅供参考');
  await expect(result).toContainText('• ');
  await expect(result).toContainText('<b>原样</b> 2 * 3 * 4');
  await expect(result.locator('b')).toHaveCount(0);
  await expect(result).not.toContainText('---');
});

test('a record can be deleted from its details and the history refreshes', async ({ page }) => {
  await bridge(page);
  await page.goto('/');
  await page.getByRole('button', { name: /整理备忘录/ }).click();
  await expect(page.locator('#record-content')).toContainText('com.example.notes');
  await page.getByRole('button', { name: '删除记录' }).click();
  await expect(page.locator('#record-dialog')).not.toBeVisible();
  await expect(page.locator('#notice')).toContainText('记录已从手机删除');
  await expect(page.locator('#records')).toContainText('还没有记录');
  expect(await page.evaluate(() => window.testCalls.find(c => c.args?.method === 'records.delete').args.params)).toEqual({ taskId: 'record-1' });
});
