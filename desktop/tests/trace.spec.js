import { test, expect } from '@playwright/test';
import { traceTimeline } from '../src/trace.js';
import { feishuLog } from './trace-fixture.js';

test('timed calls become bars grouped by the step they decided', () => {
  const { items, markers, groups } = traceTimeline(feishuLog);
  const byName = (name) => items.filter((item) => item.name === name);
  // Planning and placement run side by side from the start.
  expect(byName('规划')[0]).toMatchObject({ startTime: 3, endTime: 4984, groupId: 'prepare' });
  expect(byName('执行方式')[0]).toMatchObject({ startTime: 5, endTime: 2010, groupId: 'prepare' });
  // Calls before a step's line decided that step; calls after the last step close the task.
  expect(byName('Jev 决策（27 个候选）')[0].groupId).toBe('step-1');
  expect(byName('视觉模型').map((item) => item.groupId)).toEqual(['step-1', 'finish']);
  expect(byName('截图（失败）')[0]).toMatchObject({ groupId: 'step-2', color: '#dc2626', endTime: 23690 });
  expect(groups.map((group) => group.name)).toEqual(['准备', '第 1 步：点击「function_btn_1」', '第 2 步：点击「」', '收尾']);
  expect(markers.map((marker) => marker.label)).toEqual(['打开应用', '第 1 步', '第 2 步', '页面变化', '完成']);
  expect(markers.at(-1)).toMatchObject({ time: 64816, color: '#16a34a' });
});

test('midnight, unfinished calls, stack traces and untimed logs', () => {
  const night = traceTimeline(['23:59:59.000 → 规划', '00:00:01.000 ← 规划 2000ms']);
  expect(night.items[0]).toMatchObject({ startTime: 0, endTime: 2000 });
  const running = traceTimeline(['10:00:00.000 → 视觉模型', '10:00:03.000 异常：java.io.IOException: 断开\n\tat x.y(z.kt:1)']);
  expect(running.items[0].endTime).toBeUndefined();
  expect(running.markers).toEqual([]);
  const dropped = traceTimeline(['10:00:00.000 → 视觉模型', '10:00:01.200 × 视觉模型 取消 1200ms']);
  expect(dropped.items[0]).toMatchObject({ name: '视觉模型（已取消）', endTime: 1200, color: '#ea580c' });
  expect(traceTimeline(['12:00 读取 com.example.notes', '12:01 完成'])).toBeNull();
  expect(traceTimeline(undefined)).toBeNull();
});
