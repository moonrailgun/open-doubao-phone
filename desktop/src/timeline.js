import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { Waterfall } from 'react-waterfall-timeline';
import 'react-waterfall-timeline/style.css';
import { traceTimeline } from './trace.js';

/** Draws a task log as a waterfall in [container]; returns the unmount function, or null when the log has no timed calls. */
export function renderTimeline(container, log) {
  const timeline = traceTimeline(log);
  if (!timeline?.items.length) return null;
  const root = createRoot(container);
  root.render(createElement(Waterfall, { ...timeline, labelWidth: 240 }));
  let mounted = true;
  return () => { if (mounted) { mounted = false; root.unmount(); } };
}
