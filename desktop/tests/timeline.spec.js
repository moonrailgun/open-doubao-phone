import { test, expect } from '@playwright/test';
import { feishuLog } from './trace-fixture.js';

async function phone(page, logs) {
  await page.addInitScript((logs) => {
    window.isTauri = true;
    const records = Object.keys(logs).map((goal, index) => ({ id: `record-${index}`, goal, status: 'COMPLETED', time: 1791504000000 - index * 60000 }));
    window.__TAURI_INTERNALS__ = {
      invoke: async (command, args) => {
        if (command === 'get_snapshot') {
          return { listening: true, addresses: ['192.168.1.20'], port: 38687, connected: true, device: { id: 'phone-1', name: '测试手机' }, state: null, error: null };
        }
        if (command !== 'request') throw new Error(`Unexpected command: ${command}`);
        if (args.method === 'records.list') return records;
        if (args.method === 'records.get') {
          const record = records.find((item) => item.id === args.params.taskId);
          return { ...record, result: '已在飞书中打开与张三的单聊窗口', log: logs[record.goal] };
        }
        if (args.method === 'config.get') return { jevEndpoint: '', jevModel: '', hasJevKey: false, chatEndpoint: '', chatModel: '', hasChatKey: false, language: '' };
        if (args.method === 'apps.get') return { selected: [], apps: [] };
        if (args.method === 'tts.get') return { endpoint: '', model: '', voice: '', hasToken: false };
        throw new Error(`Unexpected method: ${args.method}`);
      },
    };
  }, logs);
}

test('record details draw the task trace as a waterfall above the plain log', async ({ page }, testInfo) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await phone(page, { 打开和张三的聊天窗口: feishuLog, 旧版本记录: ['12:00 读取 com.example.notes', '12:01 完成'] });
  await page.goto('/');
  await page.getByRole('button', { name: /打开和张三的聊天窗口/ }).click();
  const timeline = page.locator('#record-content .timeline');
  await expect(page.locator('#record-content h2').first()).toHaveText('执行时间线');
  await expect(timeline).toContainText('规划');
  await expect(timeline).toContainText('视觉模型');
  await expect(timeline).toContainText('第 1 步：点击「function_btn_1」');
  await expect(page.locator('#record-content')).toContainText('17:27:53.477 结束：COMPLETED，任务已完成');
  await page.locator('#record-dialog').screenshot({ path: testInfo.outputPath('timeline.png') });

  // Logs without timed calls (older phone builds) keep only the plain log.
  await page.locator('#record-dialog').getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByRole('button', { name: /旧版本记录/ }).click();
  await expect(page.locator('#record-content')).toContainText('12:01 完成');
  await expect(page.locator('#record-content .timeline')).toHaveCount(0);
  await expect(page.locator('#record-content h2', { hasText: '执行时间线' })).toHaveCount(0);
  expect(errors).toEqual([]);
});
