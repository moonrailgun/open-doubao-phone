// Turns a phone task log into waterfall rows. The phone writes one entry per event as `HH:mm:ss.SSS text`;
// timed calls appear as `→ name` and later `← name 123ms`, or `× name 失败|超时|取消 123ms` when they fail or are dropped.
const LINE = /^(\d{2}):(\d{2}):(\d{2})\.(\d{3}) ([\s\S]*)$/;
const STEP = /^第 (\d+) 步：(.*)$/;
const DAY = 86_400_000;
const END_LABELS = { COMPLETED: '完成', FAILED: '失败', STOPPED: '已停止' };

function colorOf(name, failed) {
  if (failed) return '#dc2626';
  if (name.startsWith('Jev')) return '#2563eb';
  if (name.startsWith('视觉模型')) return '#ea580c';
  if (name === '截图') return '#94a3b8';
  if (name === '总结') return '#16a34a';
  if (['规划', '执行方式', '意图判断', '聊天回复'].includes(name)) return '#7c3aed';
  return '#64748b';
}

/** `{ items, markers, groups }` for react-waterfall-timeline, in ms from the first entry; null when no entry has a time. */
export function traceTimeline(log) {
  const entries = [];
  let offset = 0;
  for (const line of log || []) {
    const match = LINE.exec(line);
    if (!match) continue;
    let time = ((+match[1] * 60 + +match[2]) * 60 + +match[3]) * 1000 + +match[4] + offset;
    // A task that runs past midnight starts its clock again.
    if (entries.length && time < entries.at(-1).time - DAY / 2) { offset += DAY; time += DAY; }
    entries.push({ time, text: match[5].split('\n')[0] });
  }
  if (!entries.length) return null;
  const origin = entries[0].time;
  const at = (entry) => entry.time - origin;

  // A step's line is written once its action is done, so the calls before it decided that step.
  const steps = entries.filter((entry) => STEP.test(entry.text));
  const firstPage = entries.find((entry) => entry.text.startsWith('页面 '))?.time ?? Infinity;
  const groupOf = (time) => {
    if (time < firstPage) return 'prepare';
    const done = steps.filter((step) => step.time <= time).length;
    return done < steps.length ? `step-${done + 1}` : 'finish';
  };
  const groups = [
    { id: 'prepare', name: '准备' },
    ...steps.map((step, index) => ({ id: `step-${index + 1}`, name: `第 ${index + 1} 步：${STEP.exec(step.text)[2]}` })),
    { id: 'finish', name: '收尾' },
  ];

  const items = [];
  const open = new Map();
  const markers = [];
  for (const entry of entries) {
    const text = entry.text;
    let match;
    if ((match = /^→ (.+)$/.exec(text))) {
      const item = { id: `call-${items.length}`, name: match[1], startTime: at(entry), color: colorOf(match[1], false), groupId: groupOf(entry.time) };
      items.push(item);
      open.set(match[1], [...(open.get(match[1]) || []), item]);
    } else if ((match = /^← (.+) \d+ms$/.exec(text)) || (match = /^× (.+) (失败|超时|取消) \d+ms$/.exec(text))) {
      const item = open.get(match[1])?.shift();
      if (!item) continue;
      item.endTime = at(entry);
      // A cancelled call is one the phone dropped on purpose (vision once Jev was sure), so it keeps its colour.
      if (match[2] === '取消') item.name += '（已取消）';
      else if (match[2]) { item.color = colorOf(match[1], true); item.name += `（${match[2]}）`; }
    } else if ((match = STEP.exec(text))) {
      markers.push({ id: `step-${match[1]}`, time: at(entry), label: `第 ${match[1]} 步`, color: '#0f766e' });
    } else if (text.startsWith('打开 ')) {
      markers.push({ id: `open-${markers.length}`, time: at(entry), label: '打开应用', color: '#475569' });
    } else if (text.startsWith('决策期间页面变化')) {
      markers.push({ id: `changed-${markers.length}`, time: at(entry), label: '页面变化', color: '#d97706' });
    } else if (text.startsWith('暂停：')) {
      markers.push({ id: `pause-${markers.length}`, time: at(entry), label: '暂停', color: '#d97706' });
    } else if ((match = /^结束：([A-Z]+)/.exec(text))) {
      markers.push({ id: `end-${markers.length}`, time: at(entry), label: END_LABELS[match[1]] || match[1], color: match[1] === 'COMPLETED' ? '#16a34a' : '#dc2626' });
    }
  }
  return { items, markers, groups };
}
