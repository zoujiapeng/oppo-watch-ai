/*!
 * Capsule Loader Engine — 9 种纯白发光点的胶囊加载动画
 * 供 App 内 think-lab.html（设置页调试）和 think-loader.html（悬浮球叠层）共用。
 * 背景透明：只画点，玻璃/黑底由宿主负责。
 */
(function (global) {
  "use strict";

  const TAU = Math.PI * 2;
  const clamp = (v, a, b) => v < a ? a : v > b ? b : v;
  const smooth = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };

  /* 白色光点贴图：亮核 + 5.2r 的柔光晕 */
  const SPRITE = (() => {
    const S = 128, c = document.createElement("canvas");
    c.width = c.height = S;
    const g = c.getContext("2d");
    const grd = g.createRadialGradient(S / 2, S / 2, 0, S / 2, S / 2, S / 2);
    grd.addColorStop(0,    "rgba(255,255,255,1)");
    grd.addColorStop(.07,  "rgba(255,255,255,1)");
    grd.addColorStop(.16,  "rgba(255,255,255,.92)");
    grd.addColorStop(.30,  "rgba(255,255,255,.55)");
    grd.addColorStop(.48,  "rgba(255,255,255,.22)");
    grd.addColorStop(.68,  "rgba(255,255,255,.07)");
    grd.addColorStop(.86,  "rgba(255,255,255,.016)");
    grd.addColorStop(1,    "rgba(255,255,255,0)");
    g.fillStyle = grd;
    g.beginPath(); g.arc(S / 2, S / 2, S / 2, 0, TAU); g.fill();
    return c;
  })();

  /* 胶囊内缩：cap 圆心固定在 base，半径单独内缩 —— 左右两端才会真正留边 */
  function stadium(p, w, h, inset, bounce) {
    const cy = h / 2;
    const base = Math.min(h / 2, w / 2);
    const R = Math.max(1, base - inset);
    const seg = Math.max(0, w / 2 - base);
    const x0 = w / 2 - seg, x1 = w / 2 + seg;
    const sx = clamp(p.x, x0, x1);
    let dx = p.x - sx, dy = p.y - cy;
    let d = Math.hypot(dx, dy);
    if (d > R) {
      if (d < 1e-6) { dx = 1; dy = 0; d = 1; }
      const nx = dx / d, ny = dy / d;
      p.x = sx + nx * R;
      p.y = cy + ny * R;
      if (bounce && (p.vx * nx + p.vy * ny) > 0) {
        const vn = p.vx * nx + p.vy * ny;
        p.vx -= (1 + bounce) * vn * nx;
        p.vy -= (1 + bounce) * vn * ny;
      }
    }
  }

  function rimPoint(s, w, h, inset) {
    const cy = h / 2;
    const base = Math.min(h / 2, w / 2);
    const R = Math.max(1, base - inset);
    const straight = Math.max(0, w - 2 * base);
    const left = w / 2 - straight / 2, right = w / 2 + straight / 2;
    const L = straight * 2 + TAU * R;
    s = ((s % L) + L) % L;
    if (s < straight) return [left + s, cy - R];
    s -= straight;
    if (s < Math.PI * R) {
      const a = -Math.PI / 2 + s / R;
      return [right + Math.cos(a) * R, cy + Math.sin(a) * R];
    }
    s -= Math.PI * R;
    if (s < straight) return [right - s, cy + R];
    s -= straight;
    const a = Math.PI / 2 + s / R;
    return [left + Math.cos(a) * R, cy + Math.sin(a) * R];
  }

  class Mode {
    constructor(e) { this.e = e; }
    get w() { return this.e.rect.w; }
    get h() { return this.e.rect.h; }
    get u() { return this.h / 82; }
    clear() { this.e.clear(); }
    dot(x, y, r, a) { this.e.dot(x, y, r, a); }
  }

  /* ── 01 脉冲：会呼吸的引力井 ─────────────────────────────── */
  class Pulse extends Mode {
    init() {
      const w = this.w, h = this.h;
      this.dots = Array.from({ length: 28 }, () => {
        const p = {
          x: Math.random() * w, y: Math.random() * h,
          vx: (Math.random() - .5) * 40, vy: (Math.random() - .5) * 40,
          r: (.010 + Math.random() * .009) * h,
          a: .42 + Math.random() * .48
        };
        stadium(p, w, h, .16 * h, 0);
        return p;
      });
      this.ax = w / 2; this.ay = h / 2;
    }
    frame(t, dt) {
      const w = this.w, h = this.h, u = this.u;
      const well = { x: 0, y: 0, vx: 0, vy: 0 };
      well.x = w / 2 + Math.sin(t * .47) * w * .29;
      well.y = h / 2 + Math.sin(t * .71 + 1.1) * h * .30;
      stadium(well, w, h, .24 * h, 0);
      this.ax = well.x; this.ay = well.y;

      /* 引力 / 斥力交替：先把光点吸成旋涡，再甩开撞壁 */
      const s = Math.sin(t * .88);
      const pull = (s >= 0 ? 1 : -.5) * (0.55 + 0.45 * Math.abs(s));
      const swirl = 0.55 * (0.4 + 0.6 * Math.abs(s));

      for (const p of this.dots) {
        const dx = well.x - p.x, dy = well.y - p.y;
        const d2 = dx * dx + dy * dy + 90;
        const d = Math.sqrt(d2);
        const a = Math.min(900, 230000 / d2) * pull;
        p.vx += (dx / d) * a * dt - (dy / d) * a * swirl * dt;
        p.vy += (dy / d) * a * dt + (dx / d) * a * swirl * dt;
        const damp = Math.exp(-1.05 * dt);
        p.vx *= damp; p.vy *= damp;
        p.x += p.vx * dt; p.y += p.vy * dt;
        stadium(p, w, h, .18 * h, .55);
      }
    }
    draw(t) {
      this.clear();
      /* 引力井本身也亮一下：聚拢时是一颗更亮的核 */
      const s = Math.sin(t * .88);
      const pull = Math.max(0, s);
      this.dot(this.ax, this.ay, .022 * this.h, .06 + .28 * pull);
      for (const p of this.dots) {
        const d = Math.hypot(this.ax - p.x, this.ay - p.y);
        const hot = clamp(1 - d / (this.w * .42), 0, 1);
        this.dot(p.x, p.y, p.r * (.85 + .4 * hot), p.a * (.55 + .55 * hot));
      }
    }
  }

  /* ── 02 珠链：跟着蛇头游动的光点链 ───────────────────────── */
  class Chain extends Mode {
    init() {
      const w = this.w, h = this.h;
      this.n = 10;
      this.gap = .12 * h;
      this.b = Array.from({ length: this.n }, (_, i) => ({
        x: w * .5 - i * this.gap, y: h * .5
      }));
    }
    frame(t) {
      const w = this.w, h = this.h, u = this.u;
      const head = this.b[0];
      /* 两条不同频率的 Lissajous 叠在一起 → 蛇头会绕小圈、会突然窜出去 */
      const tt = t + .38 * Math.sin(t * .61);
      head.x = w / 2 + (w / 2 - 26 * u) *
        (Math.sin(tt * .57) * .78 + Math.sin(tt * 1.13 + 1.4) * .22);
      head.y = h / 2 + (h * .30) *
        (Math.sin(tt * .83 + .9) * .80 + Math.cos(tt * 1.41) * .20);
      stadium(head, w, h, .20 * h, 0);

      /* 跟带：每一节贴着前一节，保持节距；头一甩，尾巴自然甩 */
      for (let i = 1; i < this.n; i++) {
        const a = this.b[i - 1], b = this.b[i];
        const dx = a.x - b.x, dy = a.y - b.y;
        const d = Math.hypot(dx, dy) || 1;
        const k = ((d - this.gap) / d) * .62;
        b.x += dx * k; b.y += dy * k;
        stadium(b, w, h, .17 * h, 0);
      }
    }
    draw(t) {
      this.clear();
      for (let i = 0; i < this.n; i++) {
        const b = this.b[i];
        const wv = .5 + .5 * Math.sin(t * 3.3 - i * .62);
        const r = (.024 - i * .0011) * this.h * (.82 + .34 * wv);
        this.dot(b.x, b.y, r, .40 + .60 * wv);
      }
    }
  }

  /* ── 03 螺旋：三股光点拧成的螺旋穿过胶囊 ─────────────────── */
  class Helix extends Mode {
    init() {
      const h = this.h;
      this.n = 9;
      this.strands = 3;
      this.dots = [];
      for (let s = 0; s < this.strands; s++)
        for (let i = 0; i < this.n; i++)
          this.dots.push({ i, s, ph: Math.random() * TAU, rr: .8 + Math.random() * .5 });
    }
    frame(t) {
      const w = this.w, h = this.h;
      const cy = h / 2, R = h / 2;
      const pad = Math.max(6, h * .09);
      const span = Math.max(1, w - pad * 2);
      for (const d of this.dots) {
        const u = ((d.i / this.n + d.s / this.strands + t * .05) % 1 + 1) % 1;
        const x = pad + u * span;
        /* 胶囊两端收口：螺旋跟着胶囊的轮廓收窄 */
        let half = R;
        if (x < R) half = Math.sqrt(Math.max(0, R * R - (R - x) * (R - x)));
        if (x > w - R) half = Math.sqrt(Math.max(0, R * R - (x - (w - R)) * (x - (w - R))));
        half = Math.max(0, half - 6);
        const amp = Math.min(h * .25, half * .95);
        const phase = u * TAU * 1.15 + t * 1.7 + .5 * Math.sin(t * .6) + d.s * TAU / 3;
        const depth = .5 + .5 * Math.cos(phase);
        d.x = x;
        d.y = cy + amp * Math.sin(phase);
        d.depth = depth;
        d.env = smooth(0, .055, u) * smooth(0, .055, 1 - u);
      }
    }
    draw(t) {
      this.clear();
      const h = this.h;
      for (const d of this.dots) {
        const r = (.010 + .009 * d.depth) * h * d.rr;
        const a = d.env * (.20 + .80 * d.depth);
        this.dot(d.x, d.y, r, a);
      }
    }
  }

  /* ── 04 环轨：两圈光点沿胶囊内壁反向追逐 ─────────────────── */
  class Orbit extends Mode {
    init() {
      const h = this.h;
      this.rings = [
        { inset: h * .19, n: 9, dir: 1, speed: h * 1.35, base: .017 * h, alpha: .95 },
        { inset: h * .33, n: 7, dir: -1, speed: h * 1.05, base: .0135 * h, alpha: .72 }
      ];
      for (const ring of this.rings) {
        ring.dots = Array.from({ length: ring.n }, (_, i) => ({
          s: (i / ring.n) * 700 + Math.random() * 40,
          ph: Math.random() * TAU
        }));
      }
    }
    frame(t, dt) {
      const w = this.w, h = this.h;
      for (const ring of this.rings) {
        const base = Math.min(h / 2, w / 2);
        const R = Math.max(1, base - ring.inset);
        const L = Math.max(1, Math.max(0, w - 2 * base) * 2 + TAU * R);
        for (const d of ring.dots) {
          const sp = ring.speed * (.62 + .38 * Math.sin(t * 1.15 + d.ph));
          d.s = ((d.s + ring.dir * sp * dt) % L + L) % L;
          const [x, y] = rimPoint(d.s, w, h, ring.inset);
          d.x = x; d.y = y;
        }
      }
    }
    draw(t) {
      this.clear();
      for (const ring of this.rings) {
        for (const d of ring.dots) {
          const wv = .5 + .5 * Math.sin(t * 1.7 + d.ph * 1.9);
          this.dot(d.x, d.y, ring.base * (.82 + .4 * wv), ring.alpha * (.68 + .32 * wv));
        }
      }
    }
  }

  /* ── 05 星座：同一团光点在几种形状间不断重组 ─────────────── */
  class Morph extends Mode {
    init() {
      const w = this.w, h = this.h;
      this.n = 26;
      this.dots = Array.from({ length: this.n }, (_, i) => {
        const a = (i / this.n) * TAU;
        return {
          i,
          x: w / 2 + Math.cos(a) * h * .22,
          y: h / 2 + Math.sin(a) * h * .22,
          vx: 0, vy: 0,
          ph: Math.random() * TAU,
          k: 30 + (i % 6) * 7
        };
      });
    }
    target(k, i, t) {
      const w = this.w, h = this.h, cx = w / 2, cy = h / 2;
      const n = this.n, u = i / (n - 1), m = Math.min(26, Math.max(16, w * .10));
      if (k === 0) {  /* 横线 */
        return [m + u * (w - m * 2), cy + Math.sin(i * 1.9) * 1.6];
      }
      if (k === 1) {  /* 旋转的椭圆环 */
        const a = (i / n) * TAU + t * .45;
        return [cx + Math.cos(a) * w * .26, cy + Math.sin(a) * h * .26];
      }
      if (k === 2) {  /* 中间有包络的波 */
        const x = m + u * (w - m * 2);
        return [x, cy + Math.sin(u * TAU * 1.25 - t * .9) * h * .26 * Math.sin(u * Math.PI)];
      }
      /* 收成一团更亮的星核 */
      const a = (i / n) * TAU * 2 + t * .95 + (i % 2) * .3;
      return [cx + Math.cos(a) * h * .145, cy + Math.sin(a) * h * .145];
    }
    frame(t, dt) {
      const w = this.w, h = this.h, u = this.u;
      const PER = 3.3;
      const k = Math.floor(t / PER) % 4, nk = (k + 1) % 4;
      const blend = smooth(PER - 1.05, PER, t % PER);
      for (const d of this.dots) {
        const A = this.target(k, d.i, t);
        const B = this.target(nk, d.i, t);
        const tx = A[0] + (B[0] - A[0]) * blend + Math.sin(t * 1.6 + d.ph) * 1.4;
        const ty = A[1] + (B[1] - A[1]) * blend + Math.cos(t * 1.35 + d.ph) * 1.4;
        d.vx += (tx - d.x) * d.k * dt;
        d.vy += (ty - d.y) * d.k * dt;
        const damp = Math.exp(-3.3 * dt);
        d.vx *= damp; d.vy *= damp;
        d.x += d.vx * dt; d.y += d.vy * dt;
        stadium(d, w, h, .16 * h, .25);
      }
    }
    draw(t) {
      this.clear();
      for (const d of this.dots) {
        const wv = .5 + .5 * Math.sin(t * 2.1 + d.ph);
        this.dot(d.x, d.y, (.011 + .006 * wv) * this.h, .45 + .55 * wv);
      }
    }
  }

  /* ── 06 彗尾：两颗甩着光点尾巴的彗星 ─────────────────────── */
  class Comet extends Mode {
    init() {
      this.body = [0, 1].map(i => ({ ph: i * 2.3, hist: [] }));
    }
    frame(t) {
      const w = this.w, h = this.h, pad = h * .20;
      this.body.forEach((c, i) => {
        const tt = t + .45 * Math.sin(t * .67 + i * 1.9);
        c.x = w / 2 + (w / 2 - pad) *
          (.82 * Math.sin(tt * .86 + c.ph) + .18 * Math.sin(tt * 1.97 + 1.4 + i * .8));
        c.y = h / 2 + (h / 2 - pad) *
          (.78 * Math.sin(tt * 1.17 + c.ph + 1.1) + .22 * Math.sin(tt * 2.63 + i));
        stadium(c, w, h, pad, 0);
        c.hist.unshift({ x: c.x, y: c.y });
        if (c.hist.length > 54) c.hist.pop();
      });
    }
    draw(t) {
      this.clear();
      for (const c of this.body) {
        for (let k = 9; k >= 1; k--) {
          const p = c.hist[k * 5];
          if (!p) continue;
          const f = 1 - k / 10;
          this.dot(p.x, p.y, (.008 + .015 * f) * this.h, .05 + .60 * f * f);
        }
        if (c.x != null) this.dot(c.x, c.y, .023 * this.h, .92);
      }
    }
  }

  /* ── 07 磁吸：一圈光点被游走的磁源拉成水滴 ───────────────── */
  class Magnet extends Mode {
    init() {
      const n = 22, w = this.w, h = this.h;
      const rx = w * .28, ry = h * .26;
      this.dots = Array.from({ length: n }, (_, i) => {
        const a = (i / n) * TAU;
        return {
          a,
          x: w / 2 + Math.cos(a) * rx,
          y: h / 2 + Math.sin(a) * ry,
          vx: 0, vy: 0,
          r: .011 + Math.random() * .005
        };
      });
      this.mx = w / 2; this.my = h / 2;
    }
    frame(t, dt) {
      const w = this.w, h = this.h, cx = w / 2, cy = h / 2;
      const rx = w * .28, ry = h * .26;
      this.mx = cx + Math.cos(t * .63) * rx * .55;
      this.my = cy + Math.sin(t * .91) * ry * .55;
      for (const p of this.dots) {
        const a = p.a + t * .18;
        const tx = cx + Math.cos(a) * rx;
        const ty = cy + Math.sin(a) * ry;
        p.vx += (tx - p.x) * 52 * dt;
        p.vy += (ty - p.y) * 52 * dt;
        const dx = this.mx - p.x, dy = this.my - p.y;
        const d2 = dx * dx + dy * dy + 700;
        const d = Math.sqrt(d2);
        const pull = Math.min(2200, 800000 / d2);
        p.vx += (dx / d) * pull * dt;
        p.vy += (dy / d) * pull * dt;
        const damp = Math.exp(-2.2 * dt);
        p.vx *= damp; p.vy *= damp;
        p.x += p.vx * dt; p.y += p.vy * dt;
        stadium(p, w, h, .18 * h, .15);
      }
    }
    draw(t) {
      this.clear();
      this.dot(this.mx, this.my, .018 * this.h, .08);
      for (const p of this.dots) {
        const d = Math.hypot(this.mx - p.x, this.my - p.y);
        const hot = clamp(1 - d / (this.h * .6), 0, 1);
        this.dot(p.x, p.y, p.r * this.h * (.9 + .5 * hot), .42 + .58 * hot);
      }
    }
  }

  /* ── 08 跳环：一圈光点绕着圈依次弹起 ─────────────────────── */
  class Hop extends Mode {
    init() {
      this.n = 10;
      this.dots = Array.from({ length: this.n }, (_, i) => ({ i }));
    }
    frame(t) {
      const w = this.w, h = this.h, cx = w / 2, cy = h / 2;
      const rx = w * .33, ry = h * .25;
      const rot = t * .28;
      for (const d of this.dots) {
        const a = (d.i / this.n) * TAU + rot;
        const hop = Math.max(0, Math.sin(t * 3.4 - d.i * .7));
        d.hop = hop;
        d.x = cx + Math.cos(a) * rx;
        d.y = cy + Math.sin(a) * ry - hop * h * .12;
      }
    }
    draw(t) {
      this.clear();
      for (const d of this.dots) {
        this.dot(d.x, d.y, (.014 + .004 * d.hop) * this.h, .50 + .45 * d.hop);
      }
    }
  }

  /* ── 09 涟漪：两个光源不停放出点环 ───────────────────────── */
  class Ripple extends Mode {
    init() {
      this.n = 16;
      this.per = .95;
      this.em = [{ x: .30, y: .5, ph: 0 }, { x: .70, y: .5, ph: .5 }];
    }
    frame(t) {
      const w = this.w, h = this.h;
      this.rings = [];
      this.src = [];
      for (let e = 0; e < 2; e++) {
        const em = this.em[e];
        const ex = w * em.x + Math.sin(t * .5 + e * 2.1) * w * .02;
        const ey = h * (.5 + .06 * Math.sin(t * .7 + e * 1.7));
        this.src.push({ x: ex, y: ey, ph: e });
        for (let r = 0; r < 2; r++) {
          const p = (((t / this.per + em.ph + r * .5) % 1) + 1) % 1;
          const rad = Math.pow(p, .62);
          this.rings.push({
            x: ex, y: ey, p,
            rx: w * .19 * rad, ry: h * .28 * rad,
            a: Math.sin(Math.PI * p)
          });
        }
      }
    }
    draw(t) {
      this.clear();
      for (const r of this.rings) {
        for (let i = 0; i < this.n; i++) {
          const ang = (i / this.n) * TAU + r.p * .8;
          const x = r.x + Math.cos(ang) * r.rx;
          const y = r.y + Math.sin(ang) * r.ry;
          const tw = .5 + .5 * Math.sin(i * 2.1 + r.p * 9);
          this.dot(x, y, (.008 + .005 * r.a) * this.h, r.a * (.25 + .75 * tw) * .9);
        }
      }
      for (const s of this.src)
        this.dot(s.x, s.y, .016 * this.h, .18 + .2 * Math.abs(Math.sin(t * 1.7 + s.ph * 2)));
    }
  }


  const MODES = {
    pulse: Pulse, chain: Chain, helix: Helix, orbit: Orbit, morph: Morph,
    comet: Comet, magnet: Magnet, hop: Hop, ripple: Ripple
  };
  const NAMES = ["pulse", "chain", "helix", "orbit", "morph", "comet", "magnet", "hop", "ripple"];

  class CapsuleEngine {
    constructor(canvas, opts) {
      opts = opts || {};
      this.canvas = canvas;
      this.ctx = canvas.getContext("2d");
      this.dpr = Math.min(global.devicePixelRatio || 1, 2);
      this.rect = { x: 0, y: 0, w: 1, h: 1 };
      this.rectSet = false;
      this.cssW = 1; this.cssH = 1;
      this.speed = opts.speed || 1;
      this.scale = opts.scale || 1;
      this.dotScale = opts.dotScale || 1;
      this.t = 0; this.last = 0; this.raf = 0; this.running = false;
      this.loop = this.loop.bind(this);
      this.resize();
      this.setMode(opts.mode || "pulse");
      if (opts.autoStart !== false) this.start();
    }
    resize() {
      const r = this.canvas.getBoundingClientRect();
      const w = Math.max(1, Math.round(r.width)), h = Math.max(1, Math.round(r.height));
      this.cssW = w; this.cssH = h;
      this.canvas.width = Math.round(w * this.dpr);
      this.canvas.height = Math.round(h * this.dpr);
      this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      this.ctx.imageSmoothingEnabled = true;
      if (!this.rectSet) {
        this.rect = { x: 0, y: 0, w: w, h: h };
        this.rectSet = true;
      } else {
        this.rect = { x: 0, y: 0, w: w, h: h };
      }
      if (this.mode) this.mode.init();
    }
    setRect(x, y, w, h) {
      const p = this.rect;
      const nw = Math.max(2, w), nh = Math.max(2, h);
      this.rect = { x: x, y: y, w: nw, h: nh };
      if (this.mode && (Math.abs(nw - p.w) > p.w * .02 || Math.abs(nh - p.h) > p.h * .02))
        this.mode.init();
    }
    setMode(name) {
      const C = MODES[name];
      if (!C) return;
      this.modeName = name;
      this.mode = new C(this);
      this.mode.init();
    }
    setSpeed(s) { this.speed = Math.max(.05, Math.min(4, +s || 1)); }
    setScale(s) { this.scale = Math.max(.1, Math.min(12, +s || 1)); }
    setDotScale(s) { this.dotScale = Math.max(.1, Math.min(10, +s || 1)); }
    clear() {
      const c = this.ctx;
      c.clearRect(0, 0, this.cssW, this.cssH);
      c.globalCompositeOperation = "lighter";
    }
    dot(x, y, r, a) {
      if (r <= 0) return;
      const k = this.scale || 1;
      const cx = this.rect.x + this.rect.w / 2, cy = this.rect.y + this.rect.h / 2;
      const px = cx + (this.rect.x + x - cx) * k;
      const py = cy + (this.rect.y + y - cy) * k;
      const S = r * k * (this.dotScale || 1) * 5.2, c = this.ctx;
      c.globalAlpha = clamp(a, 0, 1);
      c.drawImage(SPRITE, px - S, py - S, S * 2, S * 2);
    }
    start() {
      if (this.running) return;
      this.running = true;
      this.last = performance.now();
      this.raf = requestAnimationFrame(this.loop);
    }
    stop() {
      this.running = false;
      if (this.raf) cancelAnimationFrame(this.raf);
      this.raf = 0;
    }
    play() { this.start(); }
    pause() { this.stop(); }
    loop(now) {
      if (!this.running) return;
      const raw = Math.min(.05, Math.max(0, (now - this.last) / 1000));
      this.last = now;
      const dt = raw * this.speed;
      this.t += dt;
      if (this.mode) {
        this.mode.frame(this.t, dt);
        this.mode.draw(this.t);
      }
      this.raf = requestAnimationFrame(this.loop);
    }
  }

  global.CapsuleEngine = CapsuleEngine;
  global.CapsuleModes = NAMES;
})(window);
