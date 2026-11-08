package com.glslplayground.app;

import java.util.Random;

/**
 * 9 种胶囊加载动画的点集引擎（纯 Java，给 GlassRenderer 的点精灵 pass 用）。
 * 坐标是球的局部像素：x∈[0,w], y∈[0,h]（y 向下），w/h = 2*hx/2*hy。
 * 输出点：x, y, r（亮核半径 px）, a。
 */
public class CapsuleLoaderEngine {

    public static final int PULSE = 1, CHAIN = 2, HELIX = 3, ORBIT = 4, MORPH = 5,
            COMET = 6, MAGNET = 7, HOP = 8, RIPPLE = 9;

    private int mode = 0;
    private float w = 1f, h = 1f, t = 0f, speed = 1f;
    private Mode m;
    private final Random rnd = new Random(0x5eed);
    private final float[] out = new float[4 * 512];
    private int n = 0;

    public void setMode(int v) {
        if (v == mode) return;
        mode = v;
        m = create(v);
        if (m != null) m.init();
    }

    public void setSpeed(float s) { speed = Math.max(.05f, Math.min(4f, s)); }

    public void setSize(float nw, float nh) {
        nw = Math.max(8f, nw); nh = Math.max(8f, nh);
        if (Math.abs(nw - w) > w * .02f || Math.abs(nh - h) > h * .02f) {
            w = nw; h = nh;
            if (m != null) m.init();
        }
    }

    public void step(float dt) {
        if (m == null || mode == 0) { n = 0; return; }
        dt = Math.min(.05f, Math.max(0f, dt)) * speed;
        t += dt;
        m.step(dt);
        n = 0;
        m.emit();
    }

    public float[] data() { return out; }
    public int count() { return n; }
    public float width() { return w; }
    public float height() { return h; }

    void add(float x, float y, float r, float a) {
        if (n >= 512) return;
        int i = n * 4;
        out[i] = x; out[i + 1] = y; out[i + 2] = r; out[i + 3] = Math.max(0f, Math.min(1f, a));
        n++;
    }

    private Mode create(int v) {
        switch (v) {
            case PULSE: return new Pulse();
            case CHAIN: return new Chain();
            case HELIX: return new Helix();
            case ORBIT: return new Orbit();
            case MORPH: return new Morph();
            case COMET: return new Comet();
            case MAGNET: return new Magnet();
            case HOP: return new Hop();
            case RIPPLE: return new Ripple();
            default: return null;
        }
    }

    abstract class Mode {
        void init() { }
        abstract void step(float dt);
        abstract void emit();
        float rand(float a, float b) { return a + rnd.nextFloat() * (b - a); }
        /** 把点收进内缩后的胶囊里（cap 圆心固定，半径内缩） */
        void contain(float[] p, float inset, float bounce) {
            float cy = h / 2f;
            float base = Math.min(h / 2f, w / 2f);
            float R = Math.max(1f, base - inset);
            float seg = Math.max(0f, w / 2f - base);
            float x0 = w / 2f - seg, x1 = w / 2f + seg;
            float sx = Math.max(x0, Math.min(x1, p[0]));
            float dx = p[0] - sx, dy = p[1] - cy;
            float d = (float) Math.hypot(dx, dy);
            if (d > R) {
                if (d < 1e-6f) { dx = 1; dy = 0; d = 1; }
                float nx = dx / d, ny = dy / d;
                p[0] = sx + nx * R; p[1] = cy + ny * R;
                if (bounce > 0) {
                    float vn = p[2] * nx + p[3] * ny;
                    if (vn > 0) {
                        p[2] -= (1 + bounce) * vn * nx;
                        p[3] -= (1 + bounce) * vn * ny;
                    }
                }
            }
        }
        /** 胶囊形跑道上的点（inset 内缩） */
        float[] rim(float s, float inset) {
            float cy = h / 2f;
            float base = Math.min(h / 2f, w / 2f);
            float R = Math.max(1f, base - inset);
            float straight = Math.max(0f, w - 2 * base);
            float left = w / 2f - straight / 2f, right = w / 2f + straight / 2f;
            float L = straight * 2 + (float) (Math.PI * 2) * R;
            s = ((s % L) + L) % L;
            if (s < straight) return new float[]{left + s, cy - R};
            s -= straight;
            if (s < Math.PI * R) {
                float a = (float) (-Math.PI / 2) + s / R;
                return new float[]{right + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R};
            }
            s -= (float) Math.PI * R;
            if (s < straight) return new float[]{right - s, cy + R};
            s -= straight;
            float a = (float) (Math.PI / 2) + s / R;
            return new float[]{left + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R};
        }
    }

    /* ── 01 脉冲 ─────────────────────────────────────────────── */
    class Pulse extends Mode {
        final int N = 28;
        final float[] px = new float[N], py = new float[N], vx = new float[N], vy = new float[N],
                rr = new float[N], aa = new float[N];
        float ax, ay;
        void init() {
            for (int i = 0; i < N; i++) {
                float[] p = {rand(0, w), rand(0, h), rand(-40, 40), rand(-40, 40)};
                contain(p, .16f * h, 0);
                px[i] = p[0]; py[i] = p[1]; vx[i] = p[2]; vy[i] = p[3];
                rr[i] = rand(.010f, .019f) * h;
                aa[i] = rand(.42f, .90f);
            }
        }
        void step(float dt) {
            ax = w / 2f + (float) Math.sin(t * .47) * w * .29f;
            ay = h / 2f + (float) Math.sin(t * .71 + 1.1) * h * .30f;
            float[] well = {ax, ay, 0, 0};
            contain(well, .24f * h, 0);
            ax = well[0]; ay = well[1];
            float s = (float) Math.sin(t * .88);
            float pull = (s >= 0 ? 1f : -.5f) * (.55f + .45f * Math.abs(s));
            float swirl = .55f * (.4f + .6f * Math.abs(s));
            for (int i = 0; i < N; i++) {
                float dx = ax - px[i], dy = ay - py[i];
                float d2 = dx * dx + dy * dy + 90f;
                float d = (float) Math.sqrt(d2);
                float a = Math.min(900f, 230000f / d2) * pull;
                vx[i] += (dx / d) * a * dt - (dy / d) * a * swirl * dt;
                vy[i] += (dy / d) * a * dt + (dx / d) * a * swirl * dt;
                float damp = (float) Math.exp(-1.05f * dt);
                vx[i] *= damp; vy[i] *= damp;
                px[i] += vx[i] * dt; py[i] += vy[i] * dt;
                float[] p = {px[i], py[i], vx[i], vy[i]};
                contain(p, .18f * h, .55f);
                px[i] = p[0]; py[i] = p[1]; vx[i] = p[2]; vy[i] = p[3];
            }
        }
        void emit() {
            float s = (float) Math.sin(t * .88);
            add(ax, ay, .022f * h, .06f + .28f * Math.max(0, s));
            for (int i = 0; i < N; i++) {
                float d = (float) Math.hypot(ax - px[i], ay - py[i]);
                float hot = Math.max(0, Math.min(1, 1 - d / (w * .42f)));
                add(px[i], py[i], rr[i] * (.85f + .4f * hot), aa[i] * (.55f + .55f * hot));
            }
        }
    }

    /* ── 02 珠链 ─────────────────────────────────────────────── */
    class Chain extends Mode {
        final int N = 10;
        final float[] bx = new float[N], by = new float[N];
        float gap;
        void init() {
            gap = .12f * h;
            for (int i = 0; i < N; i++) { bx[i] = w / 2f - i * gap; by[i] = h / 2f; }
        }
        void step(float dt) {
            float u = h / 82f;
            float tt = t + .38f * (float) Math.sin(t * .61);
            bx[0] = w / 2f + (w / 2f - 26 * u) *
                    ((float) Math.sin(tt * .57) * .78f + (float) Math.sin(tt * 1.13 + 1.4) * .22f);
            by[0] = h / 2f + (h * .30f) *
                    ((float) Math.sin(tt * .83 + .9) * .80f + (float) Math.cos(tt * 1.41) * .20f);
            float[] head = {bx[0], by[0], 0, 0};
            contain(head, .20f * h, 0);
            bx[0] = head[0]; by[0] = head[1];
            for (int i = 1; i < N; i++) {
                float dx = bx[i - 1] - bx[i], dy = by[i - 1] - by[i];
                float d = (float) Math.hypot(dx, dy);
                if (d < 1e-4f) d = 1;
                float k = ((d - gap) / d) * .62f;
                bx[i] += dx * k; by[i] += dy * k;
                float[] p = {bx[i], by[i], 0, 0};
                contain(p, .17f * h, 0);
                bx[i] = p[0]; by[i] = p[1];
            }
        }
        void emit() {
            for (int i = 0; i < N; i++) {
                float wv = .5f + .5f * (float) Math.sin(t * 3.3 - i * .62);
                float r = (.024f - i * .0011f) * h * (.82f + .34f * wv);
                add(bx[i], by[i], r, .40f + .60f * wv);
            }
        }
    }

    /* ── 03 螺旋 ─────────────────────────────────────────────── */
    class Helix extends Mode {
        final int N = 9, STRANDS = 3;
        void init() { }
        void step(float dt) { }
        void emit() {
            float cy = h / 2f, R = h / 2f;
            float pad = Math.max(6f, h * .09f);
            float span = Math.max(1f, w - pad * 2);
            for (int s = 0; s < STRANDS; s++) {
                for (int i = 0; i < N; i++) {
                    float u = ((i / (float) N + s / (float) STRANDS + t * .05f) % 1f + 1f) % 1f;
                    float x = pad + u * span;
                    float half = R;
                    if (x < R) half = (float) Math.sqrt(Math.max(0, R * R - (R - x) * (R - x)));
                    if (x > w - R) half = (float) Math.sqrt(Math.max(0, R * R - (x - (w - R)) * (x - (w - R))));
                    half = Math.max(0, half - 6f);
                    float amp = Math.min(h * .25f, half * .95f);
                    float phase = u * (float) (Math.PI * 2) * 1.15f + t * 1.7f
                            + .5f * (float) Math.sin(t * .6f) + s * (float) (Math.PI * 2) / 3f;
                    float depth = .5f + .5f * (float) Math.cos(phase);
                    float env = smooth(0, .055f, u) * smooth(0, .055f, 1 - u);
                    float rr = .8f + .5f * (float) Math.abs(Math.sin(i * 7.13f + s * 2.7f));
                    add(x, cy + amp * (float) Math.sin(phase),
                            (.010f + .009f * depth) * h * rr,
                            env * (.20f + .80f * depth));
                }
            }
        }
    }

    /* ── 04 环轨 ─────────────────────────────────────────────── */
    class Orbit extends Mode {
        final float[] s = new float[9], ph = new float[9];
        final float[] s2 = new float[7], ph2 = new float[7];
        void init() {
            for (int i = 0; i < 9; i++) { s[i] = (i / 9f) * 700f + rand(0, 40); ph[i] = rand(0, 6.28f); }
            for (int i = 0; i < 7; i++) { s2[i] = (i / 7f) * 700f + rand(0, 40); ph2[i] = rand(0, 6.28f); }
        }
        void step(float dt) {
            for (int i = 0; i < 9; i++) {
                float sp = h * 1.35f * (.62f + .38f * (float) Math.sin(t * 1.15 + ph[i]));
                s[i] += sp * dt;
                float[] p = rim(s[i], .19f * h);
                s[i] = s[i]; // keep
                px(p, i, ph[i], .017f, .95f);
            }
            for (int i = 0; i < 7; i++) {
                float sp = h * 1.05f * (.62f + .38f * (float) Math.sin(t * 1.15 + ph2[i]));
                s2[i] -= sp * dt;
                float[] p = rim(s2[i], .33f * h);
                px(p, i + 9, ph2[i], .0135f, .72f);
            }
        }
        final float[] ox = new float[16], oy = new float[16], oph = new float[16], or = new float[16], oa = new float[16];
        void px(float[] p, int i, float phv, float rr, float aa) {
            ox[i] = p[0]; oy[i] = p[1]; oph[i] = phv; or[i] = rr; oa[i] = aa;
        }
        void emit() {
            for (int i = 0; i < 16; i++) {
                if (or[i] == 0) continue;
                float wv = .5f + .5f * (float) Math.sin(t * 1.7 + oph[i] * 1.9);
                add(ox[i], oy[i], or[i] * h * (.82f + .4f * wv), oa[i] * (.68f + .32f * wv));
            }
        }
    }

    /* ── 05 星座 ─────────────────────────────────────────────── */
    class Morph extends Mode {
        final int N = 26;
        final float[] px = new float[N], py = new float[N], vx = new float[N], vy = new float[N],
                ph = new float[N], kk = new float[N];
        void init() {
            for (int i = 0; i < N; i++) {
                float a = (i / (float) N) * (float) (Math.PI * 2);
                px[i] = w / 2f + (float) Math.cos(a) * h * .22f;
                py[i] = h / 2f + (float) Math.sin(a) * h * .22f;
                ph[i] = rand(0, 6.28f);
                kk[i] = 30f + (i % 6) * 7f;
            }
        }
        float[] target(int k, int i) {
            float cx = w / 2f, cy = h / 2f;
            float u = i / (float) (N - 1);
            float m = Math.min(26f, Math.max(16f, w * .10f));
            if (k == 0) return new float[]{m + u * (w - m * 2), cy + (float) Math.sin(i * 1.9f) * 1.6f};
            if (k == 1) {
                float a = (i / (float) N) * (float) (Math.PI * 2) + t * .45f;
                return new float[]{cx + (float) Math.cos(a) * w * .26f, cy + (float) Math.sin(a) * h * .26f};
            }
            if (k == 2) {
                float x = m + u * (w - m * 2);
                return new float[]{x, cy + (float) Math.sin(u * (float) (Math.PI * 2) * 1.25f - t * .9f)
                        * h * .26f * (float) Math.sin(u * Math.PI)};
            }
            float a = (i / (float) N) * (float) (Math.PI * 4) + t * .95f + (i % 2) * .3f;
            return new float[]{cx + (float) Math.cos(a) * h * .145f, cy + (float) Math.sin(a) * h * .145f};
        }
        void step(float dt) {
            float per = 3.3f;
            int k = ((int) Math.floor(t / per)) % 4, nk = (k + 1) % 4;
            float blend = smooth(per - 1.05f, per, t % per);
            for (int i = 0; i < N; i++) {
                float[] A = target(k, i), B = target(nk, i);
                float tx = A[0] + (B[0] - A[0]) * blend + (float) Math.sin(t * 1.6f + ph[i]) * 1.4f;
                float ty = A[1] + (B[1] - A[1]) * blend + (float) Math.cos(t * 1.35f + ph[i]) * 1.4f;
                vx[i] += (tx - px[i]) * kk[i] * dt;
                vy[i] += (ty - py[i]) * kk[i] * dt;
                float damp = (float) Math.exp(-3.3f * dt);
                vx[i] *= damp; vy[i] *= damp;
                px[i] += vx[i] * dt; py[i] += vy[i] * dt;
                float[] p = {px[i], py[i], vx[i], vy[i]};
                contain(p, .16f * h, .25f);
                px[i] = p[0]; py[i] = p[1]; vx[i] = p[2]; vy[i] = p[3];
            }
        }
        void emit() {
            for (int i = 0; i < N; i++) {
                float wv = .5f + .5f * (float) Math.sin(t * 2.1f + ph[i]);
                add(px[i], py[i], (.011f + .006f * wv) * h, .45f + .55f * wv);
            }
        }
    }

    /* ── 06 彗尾 ─────────────────────────────────────────────── */
    class Comet extends Mode {
        final int HIST = 54;
        final float[][] hx = new float[2][HIST], hy = new float[2][HIST];
        float[] cxp = new float[2], cyp = new float[2];
        void init() {
            for (int c = 0; c < 2; c++) {
                cxp[c] = w / 2f; cyp[c] = h / 2f;
                for (int i = 0; i < HIST; i++) { hx[c][i] = cxp[c]; hy[c][i] = cyp[c]; }
            }
        }
        void step(float dt) {
            float pad = .20f * h;
            for (int c = 0; c < 2; c++) {
                float tt = t + .45f * (float) Math.sin(t * .67f + c * 1.9f);
                float x = w / 2f + (w / 2f - pad) *
                        (.82f * (float) Math.sin(tt * .86f + c * 2.3f + c * 1.9f)
                                + .18f * (float) Math.sin(tt * 1.97f + 1.4f + c * .8f));
                float y = h / 2f + (h / 2f - pad) *
                        (.78f * (float) Math.sin(tt * 1.17f + c * 2.3f + 1.1f)
                                + .22f * (float) Math.sin(tt * 2.63f + c));
                float[] p = {x, y, 0, 0};
                contain(p, pad, 0);
                cxp[c] = p[0]; cyp[c] = p[1];
                System.arraycopy(hx[c], 0, hx[c], 1, HIST - 1);
                System.arraycopy(hy[c], 0, hy[c], 1, HIST - 1);
                hx[c][0] = cxp[c]; hy[c][0] = cyp[c];
            }
        }
        void emit() {
            for (int c = 0; c < 2; c++) {
                for (int k = 9; k >= 1; k--) {
                    int idx = k * 5;
                    if (idx >= HIST) continue;
                    float f = 1f - k / 10f;
                    add(hx[c][idx], hy[c][idx], (.008f + .015f * f) * h, .05f + .60f * f * f);
                }
                add(cxp[c], cyp[c], .023f * h, .92f);
            }
        }
    }

    /* ── 07 磁吸 ─────────────────────────────────────────────── */
    class Magnet extends Mode {
        final int N = 22;
        final float[] px = new float[N], py = new float[N], vx = new float[N], vy = new float[N],
                ang = new float[N], rr = new float[N];
        float mx, my;
        void init() {
            for (int i = 0; i < N; i++) {
                ang[i] = (i / (float) N) * (float) (Math.PI * 2);
                px[i] = w / 2f + (float) Math.cos(ang[i]) * w * .28f;
                py[i] = h / 2f + (float) Math.sin(ang[i]) * h * .26f;
                rr[i] = rand(.011f, .016f);
            }
        }
        void step(float dt) {
            mx = w / 2f + (float) Math.cos(t * .63f) * w * .28f * .55f;
            my = h / 2f + (float) Math.sin(t * .91f) * h * .26f * .55f;
            for (int i = 0; i < N; i++) {
                float a = ang[i] + t * .18f;
                float tx = w / 2f + (float) Math.cos(a) * w * .28f;
                float ty = h / 2f + (float) Math.sin(a) * h * .26f;
                vx[i] += (tx - px[i]) * 52f * dt;
                vy[i] += (ty - py[i]) * 52f * dt;
                float dx = mx - px[i], dy = my - py[i];
                float d2 = dx * dx + dy * dy + 700f;
                float d = (float) Math.sqrt(d2);
                float pull = Math.min(2200f, 800000f / d2);
                vx[i] += (dx / d) * pull * dt;
                vy[i] += (dy / d) * pull * dt;
                float damp = (float) Math.exp(-2.2f * dt);
                vx[i] *= damp; vy[i] *= damp;
                px[i] += vx[i] * dt; py[i] += vy[i] * dt;
                float[] p = {px[i], py[i], vx[i], vy[i]};
                contain(p, .18f * h, .15f);
                px[i] = p[0]; py[i] = p[1]; vx[i] = p[2]; vy[i] = p[3];
            }
        }
        void emit() {
            add(mx, my, .018f * h, .08f);
            for (int i = 0; i < N; i++) {
                float d = (float) Math.hypot(mx - px[i], my - py[i]);
                float hot = Math.max(0, Math.min(1, 1 - d / (h * .6f)));
                add(px[i], py[i], rr[i] * h * (.9f + .5f * hot), .42f + .58f * hot);
            }
        }
    }

    /* ── 08 跳环 ─────────────────────────────────────────────── */
    class Hop extends Mode {
        final int N = 10;
        void init() { }
        void step(float dt) { }
        void emit() {
            float cx = w / 2f, cy = h / 2f;
            float rx = w * .33f, ry = h * .25f;
            for (int i = 0; i < N; i++) {
                float a = (i / (float) N) * (float) (Math.PI * 2) + t * .28f;
                float hop = Math.max(0, (float) Math.sin(t * 3.4f - i * .7f));
                float x = cx + (float) Math.cos(a) * rx;
                float y = cy + (float) Math.sin(a) * ry - hop * h * .12f;
                add(x, y, (.014f + .004f * hop) * h, .50f + .45f * hop);
            }
        }
    }

    /* ── 09 涟漪 ─────────────────────────────────────────────── */
    class Ripple extends Mode {
        final int N = 16;
        final float per = .95f;
        final float[] ex = new float[2], ey = new float[2];
        void init() { }
        void step(float dt) { }
        void emit() {
            for (int e = 0; e < 2; e++) {
                ex[e] = w * (e == 0 ? .30f : .70f) + (float) Math.sin(t * .5f + e * 2.1f) * w * .02f;
                ey[e] = h * (.5f + .06f * (float) Math.sin(t * .7f + e * 1.7f));
                for (int r = 0; r < 2; r++) {
                    float p = (((t / per + (e == 0 ? 0f : .5f) + r * .5f) % 1f) + 1f) % 1f;
                    float rad = (float) Math.pow(p, .62f);
                    float rx = w * .19f * rad, ry2 = h * .28f * rad;
                    float al = (float) Math.sin(Math.PI * p);
                    for (int i = 0; i < N; i++) {
                        float ang2 = (i / (float) N) * (float) (Math.PI * 2) + p * .8f;
                        float tw = .5f + .5f * (float) Math.sin(i * 2.1f + p * 9f);
                        add(ex[e] + (float) Math.cos(ang2) * rx, ey[e] + (float) Math.sin(ang2) * ry2,
                                (.008f + .005f * al) * h, al * (.25f + .75f * tw) * .9f);
                    }
                }
            }
            for (int e = 0; e < 2; e++)
                add(ex[e], ey[e], .016f * h, .18f + .2f * Math.abs((float) Math.sin(t * 1.7f + e * 2)));
        }
    }

    static float smooth(float a, float b, float x) {
        float t = (x - a) / (b - a);
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
