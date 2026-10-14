// Siri 波 / Fluid Dots 的 GPU 数学搬到 CPU —— 与 GlassRenderer.FS_COMP 逐行对应，
// 用来在电脑上离线对比「思考中」和「聆听中」的观感，不必每次刷 APK。
// 坐标约定：c = fc - center，c.y 向上；ny = -c.y / hy（球顶 +1，球底 -1）。

export const PI = Math.PI;

// ── 与 GLSL 同名同义的小工具 ──
const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);
const mix = (a, b, t) => a + (b - a) * t;
const fract = (x) => x - Math.floor(x);
const step = (e, x) => (x < e ? 0 : 1);
const smoothstep = (e0, e1, x) => {
  const t = clamp((x - e0) / (e1 - e0 || 1e-9), 0, 1);
  return t * t * (3 - 2 * t);
};
const V = (x = 0, y = 0, z = 0) => ({ x, y, z });
const vadd = (a, b) => V(a.x + b.x, a.y + b.y, a.z + b.z);
const vsub = (a, b) => V(a.x - b.x, a.y - b.y, a.z - b.z);
const vmul = (a, b) => V(a.x * b.x, a.y * b.y, a.z * b.z);
const vscale = (a, s) => V(a.x * s, a.y * s, a.z * s);
const vpow = (a, p) => V(Math.pow(Math.max(a.x, 0), p), Math.pow(Math.max(a.y, 0), p), Math.pow(Math.max(a.z, 0), p));

// ── Siri 波（siriWaveCore）：GlassRenderer FS_COMP 里的 waveCol ──
function spectral4(s) {
  const x = s;
  return V(clamp(Math.abs(x - 3) - 1, 0, 1), clamp(2 - Math.abs(x - 2), 0, 1), clamp(2 - Math.abs(x - 4), 0, 1));
}

export function waveCol(px, py, t, spread) {
  const low = clamp(0.45 + 0.45 * Math.sin(t * 0.8) * Math.sin(t * 0.37 + 1.0), 0, 1);
  const mid = clamp(0.40 + 0.40 * Math.sin(t * 1.7 + 2.0) * Math.sin(t * 0.53), 0, 1);
  const high = clamp(0.30 + 0.30 * Math.sin(t * 2.9 + 4.0) * Math.sin(t * 0.71 + 2.0), 0, 1);
  const drift = (t % (20 * PI)) * 2.4;
  let env = Math.cos(PI * 0.5 * Math.min(Math.abs(0.9 * px), 1.0)); env *= env;
  const A1 = 0.30 + 0.01 * low * 6.0;
  const A2 = A1 + mid * 0.05 + high * 0.06;
  const AB = 2.6 + mid * 0.8 + high * 0.5;
  const th = 0.03 * spread, inten = 0.01 * (2.0 + low * 1.5);
  const soft = 0.01 * Math.max(0.0, 2.5 + mid * 0.4) * spread;
  const yMain = A1 * env * Math.sin(px * 1.1 + drift);
  const bandAmt = 1e-4 * 26000.0 * inten;
  let num = V(), den = V();
  for (let s = 0; s < 4; s++) {
    const hue = spectral4(s);
    den = vadd(den, hue);
    const ab = mix(-AB, AB, s / 3.0);
    const yL = A2 * env * Math.sin(px + drift + ab);
    const d = Math.abs(py - yL);
    const line = inten / (Math.sqrt(d * d + soft * soft) + th);
    const lo = Math.min(yMain, yL), hi = Math.max(yMain, yL);
    const dB = Math.max(0.0, Math.max(py - hi, lo - py));
    const band = bandAmt / (dB + 0.08);
    num = vadd(num, vscale(hue, line + band));
  }
  let col = V(num.x / den.x, num.y / den.y, num.z / den.z);
  const dM = Math.abs(py - yMain);
  col = vadd(col, vscale(V(1, 1, 1), 0.5 * inten / (Math.sqrt(dM * dM + soft * soft) + th)));
  col = vpow(col, 1.5);
  return vscale(col, Math.exp(-Math.pow(px * 1.2, 2.0)));
}

// ── Fluid Dots（siriFluidDotsCore）：orbScene ──
function hash11(n) { return fract(Math.sin(n * 127.1 + 311.7) * 43758.5453); }
function settleWL(tau, w, l) { return tau <= 0 ? 0 : 1.0 - Math.exp(-l * tau) * Math.cos(w * tau); }
const settleOrb = (tau) => settleWL(tau, 4.6, 3.2);
function settleCrit(tau, l) { return tau <= 0 ? 0 : 1.0 - Math.exp(-l * tau) * (1.0 + l * tau); }
function smin(a, b, k) { const h = Math.max(k - Math.abs(a - b), 0.0) / k; return Math.min(a, b) - h * h * k * 0.25; }
function hue2rgb(h) {
  h = fract(h);
  return V(clamp(Math.abs(h * 6 - 3) - 1, 0, 1), clamp(2 - Math.abs(h * 6 - 2), 0, 1), clamp(2 - Math.abs(h * 6 - 4), 0, 1));
}
function orbDotR(fi, seed, t, K) { return K.dotR + 0.010 * Math.sin(t * 1.3 + seed * 6.2831853) + 0.005 * Math.sin(t * 2.4 + fi * 1.3); }
function orbDotSD(px, py, posx, posy, r, t, fi, shapeDamp, K) {
  let dx = px - posx, dy = py - posy;
  const sq = 0.075 * (0.5 + 0.5 * Math.sin(t * 0.9 + fi * 2.0)) * shapeDamp;
  const ca = Math.cos(t * 0.35 + fi), sa = Math.sin(t * 0.35 + fi);
  // GLSL: mat2(ca,-sa, sa,ca) * dd  →  (ca*x + sa*y, -sa*x + ca*y)
  const rx = ca * dx + sa * dy, ry = -sa * dx + ca * dy;
  dx = rx * (1.0 + sq); dy = ry * (1.0 - sq);
  return Math.sqrt(dx * dx + dy * dy) - r;
}

export function orbScene(px, py, t, K) {
  const k = Math.floor(t / 6.0);
  const te = fract(t / 6.0) * 6.0;
  const tg = t % 12.0;
  const g = settleCrit((tg - 9.2) * 1.8, 7.5) - settleWL(tg - 10.0, 6.5, 4.0);
  const gC = clamp(g, 0, 1);
  const tb = tg - 10.0;
  const charge = smoothstep(-0.30, 0.0, Math.min(tb, 0.0)) * gC;
  const flash = tb > 0.0 ? Math.exp(-tb * 7.0) : 0.0;
  const gBright = mix(1.0, 0.85, gC) * (1.0 + 0.35 * charge + 1.2 * flash);
  const spc = vscale(V(0.0, 0.5, 1.0), 0.005);
  let total3 = V(1e5, 1e5, 1e5), cAcc = V(), wAcc = 1e-6;
  for (let i = 0; i < 6; i++) {
    const fi = i, seed = hash11(fi);
    const ang = fi / 6.0 * 6.2831853 + t * 0.35;
    const dirx = Math.cos(ang), diry = Math.sin(ang);
    const R = K.ringR + 0.010 * Math.sin(t * 1.0) + 0.007 * Math.sin(t * 1.3 + seed * 6.2831853);
    const pairId = fi % 3, moverLow = (k + pairId) % 2;
    const isMover = fi < 2.5 ? step(moverLow, 0.5) : step(0.5, moverLow);
    const goStart = pairId * 0.33, retStart = 0.99 + pairId * 0.33;
    const m = (settleOrb(te - goStart) - settleOrb(te - retStart)) * isMover;
    const rec = (settleOrb(te - goStart - 0.11) - settleOrb(te - retStart - 0.11)) * (1.0 - isMover);
    let rSelf = orbDotR(fi, seed, t, K);
    rSelf = mix(rSelf, K.dotR, gC);
    rSelf *= 1.0 - 0.18 * charge;
    const fj = (fi + 3) % 6, rPart = orbDotR(fj, hash11(fj), t, K);
    const deep = -(R + 0.035) - 0.12 * rPart;
    let radial = mix(R, deep, m) + 0.035 * rec;
    radial = mix(radial, 0.008, g);
    const posx = radial * dirx, posy = radial * diry;
    const sdr = orbDotSD(px - spc.x * dirx, py - spc.y * diry, posx, posy, rSelf, t, fi, 1.0 - gC, K);
    const sdg = orbDotSD(px - spc.y * dirx, py - spc.y * diry, posx, posy, rSelf, t, fi, 1.0 - gC, K);
    const sdb = orbDotSD(px - spc.z * dirx, py - spc.z * diry, posx, posy, rSelf, t, fi, 1.0 - gC, K);    total3 = V(smin(total3.x, sdr, 0.08), smin(total3.y, sdg, 0.08), smin(total3.z, sdb, 0.08));
    const hue = fract(fi / 6.0 + t * 0.06) * 0.667;
    const dotCol = vadd(V(1, 1, 1), vscale(vsub(hue2rgb(hue), V(1, 1, 1)), 0.01));
    const w = Math.exp(-sdg * 0.5);
    cAcc = vadd(cAcc, vscale(dotCol, w)); wAcc += w;
  }
  const sd3 = V(Math.max(total3.x, 0) + 1e-4, Math.max(total3.y, 0) + 1e-4, Math.max(total3.z, 0) + 1e-4);
  const core3 = V(
    clamp(K.intensity / Math.pow(sd3.x, K.falloffP), 0, 1),
    clamp(K.intensity / Math.pow(sd3.y, K.falloffP), 0, 1),
    clamp(K.intensity / Math.pow(sd3.z, K.falloffP), 0, 1));
  const edge3 = V(
    1 - smoothstep(0.02, K.fadeEnd, sd3.x),
    1 - smoothstep(0.02, K.fadeEnd, sd3.y),
    1 - smoothstep(0.02, K.fadeEnd, sd3.z));
  const bright = vmul(vmul(core3, edge3), V(gBright, gBright, gBright));
  return vscale(vmul(bright, vscale(cAcc, 1 / wAcc)), K.gain);
}

// ── 默认参数：与 GlassRenderer/wave 里的常量一一对应 ──
export const DEFAULT_K = {
  orbRadiusPx: 90,       // 圆点环半径（屏幕像素）——「球里放得下」由这个数直接决定
  orbOffsetNy: -0.03,    // 圆点环心的 ny 偏移（负=更靠球底），避开文字
  orbScaleY: 1.0,        // 新代码：x/y 同倍率（=1 即各向同性，圆点是圆的）
  orbFadeIn: 0.25,       // 思考中淡入时长(s)；0 = 干脆换
  ringR: 0.17,
  dotR: 0.038,
  intensity: 0.0035,
  falloffP: 1.35,
  fadeEnd: 0.56,
  colorBoost: 1.0 / 1.2,
  glow: 1.0,
  gain: 1.1,             // 与 Siri 波在黑区里的亮度对齐（原来 1.0 等于波的 4 倍亮）
};

/**
 * 思考中模式的坐标倍率（屏幕像素 → orbScene 的 p）。
 * 波坐标是 wp = c / uHalf / uWaveScale；圆点沿用同一个框架，只是整体缩放不同。
 *   k      = ringR / uOrbRpx            ← uOrbRpx = 环半径（屏幕像素），由「球里放得下」定
 *   orbP.x = cxx * k   （cyy 同倍率 → 各向同性，圆点在任何球里都是圆的）
 * 旧实现是 (cxx / hx / uWaveScale) * 0.42，x/y 同一个倍率：竖轴相当于又被放大 hy/hx 倍，
 * 2:1 的胶囊球里圆点被拉成 ~2.8 倍高的椭圆 —— 这就是「太大 / 比例不对」的来源。
 */
export function orbScales(hx, hy, K) {
  const k = Math.max(K.ringR, 1e-6) / Math.max(K.orbRadiusPx, 1e-3);
  if (K.coordMode === 'old') {
    const kOld = 0.42 / (Math.max(hx, 1e-6) * 0.85);
    return { x: kOld, y: kOld };
  }
  return { x: k, y: k * K.orbScaleY };
}

/** 与 FS_COMP main() 一致的合成：返回 [r,g,b] 增量（未乘 uWaveAmp / waveVis）与 glow。 */
export function overlay(ny, cxx, cyy, t, b1, blackMask, topDarken, hx, hy, waveAmp, waveScale, mode, K) {
  if (waveAmp <= 0.001) return { col: V(), glow: V() };
  const waveVis = 1 - blackMask * topDarken * 0.85;
  const inv = 1 / Math.max(waveScale, 0.1);
  const wpx = cxx * inv / hx;
  const wpy = (cyy * inv / hy - b1);
  if (mode === 'wave') {
    const g = waveCol(wpx, wpy, t, 7.0);
    const glow = vmul(g, V(0.80, 0.95, 1.25));
    const below = smoothstep(0.7, -0.5, wpy);
    const second = waveCol(wpx, wpy, t, 1.0);
    const add = vadd(second, vscale(glow, 0.25 + 0.6 * below));
    return { col: vscale(add, waveAmp * waveVis), glow: vscale(glow, waveAmp) };
  }
  const os = orbScales(hx, hy, K);
  const py = cyy - K.orbOffsetNy * hy;         // 环心相对球心下移
  let oc = orbScene(cxx * os.x, py * os.y, t, K);
  oc = vpow(oc, K.colorBoost);
  const glow = vscale(V(0.80, 0.95, 1.25), K.glow);
  if (mode === 'dotsonly') return { col: oc, glow: glow };
  return { col: vscale(oc, K.gain * waveAmp * waveVis), glow: vscale(glow, waveAmp) };
}
