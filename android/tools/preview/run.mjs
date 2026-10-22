// CLI：渲染「波形态 / 思考中」球体对比图 + 增益统计。
//   node run.mjs --bg synthetic|capture|dark [--out x.png] [--t 4] [--modes wave,old,new]
import path from 'node:path';
import { defaultUi, syntheticDesktop, loadBg, render, gainMetrics, rimMetrics, saveFrame, stripFrames } from './render.mjs';
import { DEFAULT_K } from './shader.mjs';

const args = process.argv.slice(2);
const arg = (name, def) => { const i = args.indexOf('--' + name); return i >= 0 ? args[i + 1] : def; };
const bgKind = arg('bg', 'synthetic');
const outPath = arg('out', '');
const ui = defaultUi({ t: parseFloat(arg('t', '4.0')), noGlow: arg('noglow', '0') === '1' });
const here = path.dirname(new URL(import.meta.url).pathname);
const outPathResolved = outPath || path.join(here, '../../../.preview/ball-compare.png');
const capturePath = arg('capture', path.join(here, '../../../.preview/ball-capture.png'));

const bg = bgKind === 'capture'
  ? loadBg(capturePath, ui.winW, ui.winH, 805 / 2, 591 / 2)
  : syntheticDesktop(ui.winW, ui.winH, { dark: bgKind === 'dark' });

const num = (k, d) => parseFloat(arg(k, String(d)));
const K_old = { ...DEFAULT_K, coordMode: 'old', gain: 1.0 };
const K_new = {
  ...DEFAULT_K, coordMode: 'new',
  orbRadiusPx: num('radius', DEFAULT_K.orbRadiusPx),
  orbOffsetNy: num('offny', DEFAULT_K.orbOffsetNy),
  orbScaleY: num('orby', 1.0),
  orbFadeIn: num('fadein', DEFAULT_K.orbFadeIn),
  gain: num('gain', 1.0), intensity: num('intensity', DEFAULT_K.intensity),
  dotR: num('dotr', DEFAULT_K.dotR), ringR: num('ringr', DEFAULT_K.ringR),
  fadeEnd: num('fadeend', DEFAULT_K.fadeEnd), glow: num('glow', DEFAULT_K.glow), rimScale: num('rim', DEFAULT_K.rimScale),
};

const baseFrame = render(ui, bg, 'none', K_new);   // 无光效基准
const rows = [], frames = [];
for (const m of (arg('modes', 'wave,old,new')).split(',')) {
  const K = m === 'old' ? K_old : K_new;
  const kind = (m === 'wave' || m === 'none' || m === 'blend' || m === 'dots') ? m : m;
  const f = render(ui, bg, kind, K);
  frames.push(f);
  rows.push({ mode: m, ...gainMetrics(f, baseFrame, ui), ...rimMetrics(f, baseFrame, ui) });
}
const strip = stripFrames(frames, 6);
saveFrame(outPathResolved, strip);
console.log(JSON.stringify({ out: outPathResolved, bg: bgKind, t: ui.t, blackOffset: ui.blackOffset, rows }, null, 1));
