// 球体渲染预览：把 GlassRenderer.FS_COMP 的合成流程（黑区/波/流体点/赤道线/球壳）在 CPU 上跑一遍，
// 输出 PNG 供离线对比。用法见同目录 README.md。
import fs from 'node:fs';
import { decodePng, encodePng } from './png.mjs';
import * as S from './shader.mjs';

const { V, PI } = S;
const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);
const smoothstep = (e0, e1, x) => { const t = clamp((x - e0) / (e1 - e0 || 1e-9), 0, 1); return t * t * (3 - 2 * t); };
const length2 = (x, y) => Math.hypot(x, y);

// ── ui 参数：默认取这台机器上的实际设定（prefs: 10sp / dy 42 / wavegap 95 / master .98 …）──
export function defaultUi(over = {}) {
  return Object.assign({
    winW: 805, winH: 591,
    screenW: 1080, screenH: 2412,
    winX: 0, winY: 0,
    refSize: 1080,
    master: 0.98, halfX: 0.4245, halfY: 0.2004, corner: 1.0,
    refraction: 87, band: 170, dome: 0.40, aberration: 26, zoom: 1.0,
    blur: 0.3, topDarken: 0.93, black0: -1.0, black1: -0.01,
    edgeBleed: 0.11, waveAmp: 1.90, waveScale: 0.85,
    noGlow: false,         // 调试：去掉赤道线/球壳上的 glow 叠加，只看光效本体
    blackOffset: 0.44,
    orbFadeT: 99,          // 距切换过了多久（s）：> orbFadeIn 即完全体
    t: 4.0,
  }, over);
}

// ── 背景：合成一个“桌面”（渐变 + 卡片 + 假文字），方便看折射/黑区/波形的关系 ──
export function syntheticDesktop(w, h, opts = {}) {
  const dark = !!opts.dark;
  const buf = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 4;
      const u = x / w, v = y / h;
      let r, g, b;
      if (dark) {
        const t = 0.05 + 0.16 * v;
        r = 255 * (t + 0.05 * Math.sin(u * 6.0)); g = 255 * (t + 0.03 * Math.cos(u * 5.0 + 1)); b = 255 * (t + 0.08);
        if (x > 40 && x < w - 40 && y > 40 && y < 190) { r = 32; g = 34; b = 42; }         // 深色卡片
      } else {
        r = 236 + 14 * v; g = 238 + 12 * v; b = 244 + 8 * v;
        if (x > 30 && x < w - 30 && y > 60 && y < 210) { r = 250; g = 250; b = 252; }        // 白卡片
        if (y > h - 150) { r = 90 + 80 * u; g = 120 + 60 * (1 - u); b = 200; }              // 底部彩色条
      }
      // 假文字行
      if (y > 100 && y < 104 && (x >> 4) % 3 !== 0) { r *= 0.25; g *= 0.25; b *= 0.3; }
      if (y > 130 && y < 134 && (x >> 4) % 4 !== 0) { r *= 0.3; g *= 0.3; b *= 0.35; }
      buf[o] = r | 0; buf[o + 1] = g | 0; buf[o + 2] = b | 0; buf[o + 3] = 255;
    }
  }
  return { w, h, data: buf };
}

export function loadBg(path, w, h, cx, cy) {
  const img = decodePng(fs.readFileSync(path));
  const buf = Buffer.alloc(w * h * 4, 255);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const sx = clamp(Math.round(cx + x - w / 2), 0, img.w - 1);
      const sy = clamp(Math.round(cy + y - h / 2), 0, img.h - 1);
      const so = (sy * img.w + sx) * 4, o = (y * w + x) * 4;
      buf[o] = img.data[so]; buf[o + 1] = img.data[so + 1]; buf[o + 2] = img.data[so + 2]; buf[o + 3] = 255;
    }
  }
  return { w, h, data: buf };
}

function blurCopy(bg, radius) {
  if (radius < 1) return bg;
  const { w, h, data } = bg;
  const out = Buffer.alloc(data.length);
  const r = Math.max(1, Math.round(radius));
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let sr = 0, sg = 0, sb = 0, n = 0;
      for (let dy = -r; dy <= r; dy += r) {
        for (let dx = -r; dx <= r; dx += r) {
          const sx = clamp(x + dx, 0, w - 1), sy = clamp(y + dy, 0, h - 1);
          const so = (sy * w + sx) * 4;
          sr += data[so]; sg += data[so + 1]; sb += data[so + 2]; n++;
        }
      }
      const o = (y * w + x) * 4;
      out[o] = sr / n; out[o + 1] = sg / n; out[o + 2] = sb / n; out[o + 3] = 255;
    }
  }
  return { w, h, data: out };
}

/** 渲染一帧（mode: 'wave' | 'dots' | 'none'）。返回 {w,h,data} RGBA。 */
export function render(ui, bg, mode, K) {
  const { winW: w, winH: h } = ui;
  const base = ui.refSize * 0.9;
  const hx = base * ui.halfX * ui.master, hy = base * ui.halfY * ui.master;
  const cx = w * 0.5, cy = h * 0.5;
  const cr = base * ui.halfY * ui.corner * ui.master;
  const blurBg = blurCopy(bg, Math.max(0, Math.round(ui.blur * 6)));
  const out = Buffer.alloc(w * h * 4);
  const b0 = ui.black0 - ui.blackOffset, b1 = ui.black1 - ui.blackOffset;
  const gradRadius = Math.min(cr * 1.5, Math.min(hx, hy));
  const depthEffect = ui.dome * 1.2;

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 4;
      const cxx = x + 0.5 - cx, cyy = -(y + 0.5 - cy);   // c.y 向上
      // sdRoundRect
      const qx = Math.abs(cxx) - (hx - cr), qy = Math.abs(cyy) - (hy - cr);
      let sd = length2(Math.max(qx, 0), Math.max(qy, 0)) - cr + Math.min(Math.max(qx, qy), 0);
      if (sd > 1.0) { for (let k = 0; k < 4; k++) out[o + k] = 0; continue; }
      sd = Math.min(sd, 0);

      const xr = 1.0 - (-sd / Math.max(ui.band * ui.master, 1.0));
      const d = (1.0 - Math.sqrt(Math.max(1.0 - clamp(xr, 0, 1) ** 2, 0.0))) * ui.refraction * ui.master;
      // gradSdRoundRect
      let gx, gy;
      if (qx >= 0 || qy >= 0) {
        const l = length2(Math.max(qx, 0), Math.max(qy, 0)) + 1e-6;
        gx = Math.sign(cxx) * Math.max(qx, 0) / l; gy = Math.sign(cyy) * Math.max(qy, 0) / l;
      } else {
        const s = qy <= qx ? 1 : 0; gx = Math.sign(cxx) * s; gy = Math.sign(cyy) * (1 - s);
      }
      const gl = length2(gx, gy) + 1e-6;
      const cl = length2(cxx, cyy) + 1e-5;
      gx = (gx / gl + depthEffect * cxx / cl); gy = (gy / gl + depthEffect * cyy / cl);
      const gl2 = length2(gx, gy) + 1e-6; gx /= gl2; gy /= gl2;

      const ny = -cyy / hy;
      const blackMaskRaw = smoothstep(b0, b1, ny);
      const blackMask = blackMaskRaw * blackMaskRaw * (3 - 2 * blackMaskRaw);
      const blackAmt = blackMask * ui.topDarken;

      // 折射采样（screen 坐标 → 桌面背景）
      const dispI = ui.aberration * ((cxx * cyy) / (hx * hy)) / 30.0;
      const sxd = gx * d * dispI, syd = -gy * d * dispI;
      const sux = (x + 0.5) + gx * d / ui.zoom, suy = (y + 0.5) - gy * d / ui.zoom;
      const sample = (tex, px, py) => {
        const sx = clamp(Math.round(px), 0, tex.w - 1), sy = clamp(Math.round(py), 0, tex.h - 1);
        const so = (sy * tex.w + sx) * 4;
        return [tex.data[so], tex.data[so + 1], tex.data[so + 2]];
      };
      const rgb = sample(blurBg, sux + sxd, suy + syd);
      const gg = sample(blurBg, sux, suy);
      const bb = sample(blurBg, sux - sxd, suy - syd);
      let R = rgb[0], G = gg[1], B = bb[2];
      // mix(col, col*tint + k, 0.22)
      R = R * 0.78 + (R * 0.94 + 0.01 * 255) * 0.22;
      G = G * 0.78 + (G * 0.96 + 0.012 * 255) * 0.22;
      B = B * 0.78 + (B * 1.00 + 0.02 * 255) * 0.22;
      // 黑区
      const dark = [0.012 * 255, 0.015 * 255, 0.025 * 255];
      R = R * (1 - blackAmt) + dark[0] * blackAmt;
      G = G * (1 - blackAmt) + dark[1] * blackAmt;
      B = B * (1 - blackAmt) + dark[2] * blackAmt;

      // 波 / 流体点
      let glow = [0, 0, 0];
      if (ui.waveAmp > 0.001 && mode !== 'none') {
        const A = ui.waveAmp;
        if (mode === 'blend') {
          // 波 ↔ 圆点 交叉淡入淡出（uOrbMix 0→1）：形变切换不再「啪」地跳一下
          const m = clamp(ui.orbFadeT / Math.max(K.orbFadeIn, 1e-3), 0, 1);
          const a = S.overlay(ny, cxx, cyy, ui.t, b1, blackMask, ui.topDarken, hx, hy, A, ui.waveScale, 'wave', K);
          const b = S.overlay(ny, cxx, cyy, ui.t, b1, blackMask, ui.topDarken, hx, hy, A, ui.waveScale, 'dots', K);
          const w = 1 - m;
          R += (a.col.x * w + b.col.x * m) * 255;
          G += (a.col.y * w + b.col.y * m) * 255;
          B += (a.col.z * w + b.col.z * m) * 255;
          glow = [a.glow.x * w + b.glow.x * m, a.glow.y * w + b.glow.y * m, a.glow.z * w + b.glow.z * m];
        } else {
          const res = S.overlay(ny, cxx, cyy, ui.t, b1, blackMask, ui.topDarken, hx, hy, A, ui.waveScale, mode, K);
          R += res.col.x * 255; G += res.col.y * 255; B += res.col.z * 255;
          glow = [res.glow.x, res.glow.y, res.glow.z];
        }
      }

      // 边缘变暗 + 赤道线 + 底部轮缘 + 球壳
      const innerEdge = smoothstep(0, -18 * ui.master, sd);
      const dim = 0.70 + (1 - 0.70) * innerEdge;
      R *= dim; G *= dim; B *= dim;
      const mid = (b0 + b1) * 0.5;
      const eqLine = smoothstep(b0, mid, ny) * smoothstep(b1, mid, ny);
      const horiz = 1 - Math.abs(cxx) / hx * 0.3;
      const eq = eqLine * horiz * ui.edgeBleed;
      R += 0.95 * 255 * eq; G += 0.97 * 255 * eq; B += 1.0 * 255 * eq;
      const bottomRim = smoothstep(-14 * ui.master, -2 * ui.master, sd) * smoothstep(0.0, -0.6, -ny);
      const br = bottomRim * ui.edgeBleed * 0.5;
      R += 0.85 * 255 * br; G += 0.88 * 255 * br; B += 0.95 * 255 * br;
      const gk = (eqLine * horiz + bottomRim) * 0.9;
      R += glow[0] * ui.waveAmp * gk * 255; G += glow[1] * ui.waveAmp * gk * 255; B += glow[2] * ui.waveAmp * gk * 255;
      const shell = smoothstep(3.0 * ui.master, 0.0, Math.abs(sd + 2.5 * ui.master));
      const shellLit = 0.30 + 0.70 * smoothstep(0.3, -0.9, ny);
      const sk = shell * shellLit * (ui.noGlow ? 0 : 1);
      R += (0.10 * 255 + glow[0] * ui.waveAmp * 1.2 * 255) * sk;
      G += (0.11 * 255 + glow[1] * ui.waveAmp * 1.2 * 255) * sk;
      B += (0.13 * 255 + glow[2] * ui.waveAmp * 1.2 * 255) * sk;

      const aa = smoothstep(1.5, -1.5, sd);
      out[o] = clamp(R * aa, 0, 255); out[o + 1] = clamp(G * aa, 0, 255); out[o + 2] = clamp(B * aa, 0, 255);
      out[o + 3] = clamp(aa * 255, 0, 255);
    }
  }
  return { w, h, data: out };
}

// ── 统计：只看球内、文字下方那条“黑区带”，比较两种形态的亮度和覆盖 ──
export function stats(frame, ui, mode) {
  const base = ui.refSize * 0.9;
  const hx = base * ui.halfX * ui.master, hy = base * ui.halfY * ui.master;
  const cx = frame.w * 0.5, cy = frame.h * 0.5;
  const b1 = ui.black1 - ui.blackOffset;
  // 采样带：黑区内、文字下方（ny 从 b1+0.02 向上到 +0.15）
  const nyLo = b1 + 0.02, nyHi = Math.min(1.0, b1 + 0.75);
  let n = 0, sum = 0, hot = 0, peak = 0, sumAll = 0, nAll = 0;
  let minY = 1e9, maxY = -1e9, minX = 1e9, maxX = -1e9;
  for (let y = 0; y < frame.h; y++) {
    for (let x = 0; x < frame.w; x++) {
      const cxx = x + 0.5 - cx, cyy = -(y + 0.5 - cy);
      if ((cxx / hx) ** 2 + (cyy / hy) ** 2 > 1) continue;
      const ny = cyy / hy;
      const o = (y * frame.w + x) * 4;
      const lum = (0.2126 * frame.data[o] + 0.7152 * frame.data[o + 1] + 0.0722 * frame.data[o + 2]) / 255;
      sumAll += lum; nAll++;
      if (ny < nyLo || ny > nyHi) continue;
      n++; sum += lum;
      if (lum > 0.5) hot++;
      if (lum > peak) peak = lum;
      if (lum > 0.22) { if (y < minY) minY = y; if (y > maxY) maxY = y; if (x < minX) minX = x; if (x > maxX) maxX = x; }
    }
  }
  return {
    mode,
    bandY: [nyLo.toFixed(2), nyHi.toFixed(2)],
    bandMean: n ? (sum / n).toFixed(4) : '0',
    bandHot: n ? (hot / n * 100).toFixed(2) + '%' : '0%',
    bandPeak: peak.toFixed(3),
    ballMean: nAll ? (sumAll / nAll).toFixed(4) : '0',
    cluster: maxY > -1e8 ? { w: maxX - minX, h: maxY - minY, cx: (minX + maxX) / 2 - cx, cy: (minY + maxY) / 2 - cy } : null,
  };
}

// ── 增益统计：把「有光效」和「无光效」两帧相减，只看光效本身加了多少亮度 ──
// 这才是两种形态该对齐的量：黑区里平均加了多少亮、峰值多亮、光斑范围多大（像素）。
export function gainMetrics(frame, base, ui) {
  const baseFx = ui.refSize * 0.9;
  const hx = baseFx * ui.halfX * ui.master, hy = baseFx * ui.halfY * ui.master;
  const cx = frame.w * 0.5, cy = frame.h * 0.5;
  const b1 = ui.black1 - ui.blackOffset;
  const nyLo = b1 + 0.02;
  let n = 0, sum = 0, peak = 0, sumBall = 0, nBall = 0, px = 0;
  let minx = 1e9, maxx = -1e9, miny = 1e9, maxy = -1e9;
  for (let y = 0; y < frame.h; y++) {
    for (let x = 0; x < frame.w; x++) {
      const cxx = x + 0.5 - cx, cyy = -(y + 0.5 - cy);
      if ((cxx / hx) ** 2 + (cyy / hy) ** 2 > 1) continue;
      const ny = cyy / hy;
      if (ny < nyLo) continue;
      const o = (y * frame.w + x) * 4;
      const lum = (0.2126 * frame.data[o] + 0.7152 * frame.data[o + 1] + 0.0722 * frame.data[o + 2]) / 255;
      const lum0 = (0.2126 * base.data[o] + 0.7152 * base.data[o + 1] + 0.0722 * base.data[o + 2]) / 255;
      const g = Math.max(0, lum - lum0);
      nBall++; sumBall += g;
      n++; sum += g;
      if (g > peak) peak = g;
      if (g > 0.25) {
        px++;
        if (x < minx) minx = x; if (x > maxx) maxx = x;
        if (y < miny) miny = y; if (y > maxy) maxy = y;
      }
    }
  }
  return {
    addMean: +(n ? sum / n : 0).toFixed(4),
    addPeak: +peak.toFixed(3),
    hotPx: px,
    clusterW: maxx > -1e8 ? maxx - minx : 0,
    clusterH: maxy > -1e8 ? maxy - miny : 0,
    clusterCyOff: maxy > -1e8 ? +(((miny + maxy) / 2) - cy).toFixed(0) : null,
    ballSpread: +(sumBall / Math.max(1, nBall)).toFixed(4),
    bandPx: n,
  };
}

export function saveFrame(path, frame) {
  fs.writeFileSync(path, encodePng(frame.w, frame.h, Buffer.from(frame.data)));
}

/** 横向拼图（带 2px 分隔），用于一张图里对比多个形态。 */
export function stripFrames(frames, gap = 4) {
  const h = Math.max(...frames.map((f) => f.h));
  const w = frames.reduce((a, f) => a + f.w, 0) + gap * (frames.length - 1);
  const out = Buffer.alloc(w * h * 4, 24);
  let xoff = 0;
  for (const f of frames) {
    for (let y = 0; y < f.h; y++) {
      for (let x = 0; x < f.w; x++) {
        const so = (y * f.w + x) * 4, o = (y * w + x + xoff) * 4;
        out[o] = f.data[so]; out[o + 1] = f.data[so + 1]; out[o + 2] = f.data[so + 2]; out[o + 3] = 255;
      }
    }
    xoff += f.w + gap;
  }
  return { w, h, data: out };
}

void V; void PI;
