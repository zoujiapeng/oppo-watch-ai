/**
 * dsh-liquid-glass-siri — browser (client) half.
 *
 * An Apple-style "Liquid Glass" Siri orb frontend for the DeepSeek Harness web
 * UI. It renders a full-screen voice surface (WebGL liquid-glass crystal ball +
 * Siri wave / fluid-dots light effects) over the running DSH app and drives the
 * real backend:
 *
 *   microphone → PCM16 WAV → `ctx.remote.speech.transcribe`
 *              → `sessions.prompt` on the session currently shown
 *              → streamed assistant text → Web Speech synthesis
 *
 * This file is a CLASSIC script served verbatim as
 * `<webRoot>/plugins/dsh-liquid-glass-siri/client.js`. Constraints (verified
 * against @deepseek-ai/dsh-client-modules):
 *
 *   1. No top-level `import`/`export`: the bundle is appended with a plain
 *      `<script src>` tag. Only the `__ModuleLoader__.load(...)` registration
 *      may run at script-execution time.
 *   2. `id` MUST equal the package name.
 *   3. All side effects live inside the factory, which runs once; its return
 *      value is memoized as `module.exports` and IS the cordis plugin object
 *      (`.apply`, `.inject`, `.name` are read off it).
 *   4. `require()` only resolves the module table's platform seed words, so
 *      this module deliberately requires nothing at all.
 */
window.__ModuleLoader__.load({
  id: "dsh-liquid-glass-siri",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var PLUGIN_ID = "dsh-liquid-glass-siri";

    // ─────────────────────────────────────────────────────────────────────
    // Tunables — the visual defaults reproduce the "iOS 26 liquid glass Siri
    // orb" look and can be overridden per plugin row through the host config.
    // ─────────────────────────────────────────────────────────────────────
    var DEFAULTS = {
      /** Master radius of the ball, in fractions of min(viewport)/2. */
      scale: 0.62,
      /** Lens refraction strength (displacement at the rim). */
      refraction: 0.42,
      /** Width of the refracting rim band, in normalized orb units. */
      band: 0.62,
      /** Radial "dome" term mixed into the lens gradient. */
      dome: 0.40,
      /** Chromatic aberration amount across the rim. */
      aberration: 0.055,
      /** 0 = no dark mirror on the upper half, 1 = fully black. */
      topDarken: 0.84,
      /** Upper-half mirror window (normalized orb y). */
      black0: -0.15,
      black1: 0.75,
      /** Equator line / bottom rim / shell highlight strength. */
      edgeBleed: 0.18,
      /** Overall gain. */
      brightness: 1.06,
      /** Internal light (Siri wave + fluid dots) gain. */
      fx: 1.0,
      /** Internal render scale (lower = faster, softer). */
      renderScale: 0.72,
      /** Language hint passed to the speech provider. */
      language: "zh",
      /** Start listening as soon as the surface opens. */
      autoListen: true,
      /** Keep listening again after every answer (hands-free conversation). */
      continuous: false,
      /** Speak answers with the browser speech synthesiser. */
      speakReplies: true,
      /** Keyboard shortcut that toggles the surface (KeyboardEvent.code). */
      hotkey: "KeyS",
      /** Show the always-available floating glass orb launcher. */
      launcher: true,
    };

    // ─────────────────────────────────────────────────────────────────────
    // Stylesheet. Everything is namespaced under .dslgs- and the plugin never
    // restyles host elements, so the stock DSH UI keeps its own appearance.
    // ─────────────────────────────────────────────────────────────────────
    var CSS = `
.dslgs-root{
  --dslgs-ease:cubic-bezier(.22,1,.36,1);
  position:fixed; inset:0; z-index:2147482000;
  display:flex; flex-direction:column; align-items:center; justify-content:center;
  font-family:-apple-system,BlinkMacSystemFont,"SF Pro Text","PingFang SC","Hiragino Sans GB","Noto Sans SC","Microsoft YaHei",system-ui,sans-serif;
  color:#f6f7fb; -webkit-font-smoothing:antialiased; text-rendering:optimizeLegibility;
  /* The root is always hit-test transparent; the launcher opts back in and the
     open state opts the surface in. Opacity lives on the CHILDREN because a
     transparent root would hide the launcher too. */
  pointer-events:none;
  contain:layout style paint;
}
.dslgs-root *{ box-sizing:border-box; }

/* ── the surface fades as a group; the launcher is independent ───────── */
.dslgs-scrim,.dslgs-edge,.dslgs-stage,.dslgs-dock,.dslgs-close{
  opacity:0; pointer-events:none;
  transition:opacity .42s var(--dslgs-ease), transform .42s var(--dslgs-ease);
}
.dslgs-stage{ transform:scale(1.02); }
.dslgs-root[data-open="true"] .dslgs-scrim,
.dslgs-root[data-open="true"] .dslgs-edge{ opacity:1; }
.dslgs-root[data-open="true"] .dslgs-stage,
.dslgs-root[data-open="true"] .dslgs-dock,
.dslgs-root[data-open="true"] .dslgs-close{
  opacity:1; pointer-events:auto; transform:none;
}

/* ── boot self-diagnosis: only ever visible when apply() throws ──────── */
.dslgs-boot-error{
  position:fixed; left:12px; right:12px; bottom:12px; z-index:2147483000;
  padding:12px 14px; border-radius:12px; font:12.5px/1.5 ui-monospace,Menlo,monospace;
  background:rgba(180,36,28,.94); color:#fff; white-space:pre-wrap;
}

/* ── backdrop: the live DSH UI stays visible but recedes ─────────────── */
.dslgs-scrim{
  position:absolute; inset:0; z-index:0; pointer-events:none;
  background:
    radial-gradient(120% 78% at 50% 116%, rgba(64,92,190,.30), rgba(0,0,0,0) 62%),
    radial-gradient(90% 60% at 50% -14%, rgba(255,110,190,.16), rgba(0,0,0,0) 60%),
    rgba(4,6,12,.70);
  -webkit-backdrop-filter:blur(34px) saturate(150%);
  backdrop-filter:blur(34px) saturate(150%);
  opacity:0; transition:opacity .5s var(--dslgs-ease);
}
.dslgs-root[data-open="true"] .dslgs-scrim{ opacity:1; }
/* ── audio-reactive glow hugging the viewport edges ──────────────────── */
.dslgs-edge{
  position:absolute; inset:0; z-index:1; pointer-events:none; opacity:0;
  transition:opacity .6s var(--dslgs-ease);
  background:
    radial-gradient(120% 40% at 50% 108%, rgba(120,170,255,var(--dslgs-edge-a,.26)), rgba(0,0,0,0) 70%),
    radial-gradient(120% 40% at 50% -8%, rgba(255,140,220,calc(var(--dslgs-edge-a,.26) * .7)), rgba(0,0,0,0) 70%);
  mix-blend-mode:screen;
}
.dslgs-root[data-open="true"] .dslgs-edge{ opacity:1; }

/* ── stage ───────────────────────────────────────────────────────────── */
.dslgs-stage{
  position:relative; z-index:2; flex:1 1 auto; width:100%;
  display:flex; flex-direction:column; align-items:center; justify-content:center;
  gap:clamp(10px,2.4vh,26px); padding:max(24px,env(safe-area-inset-top)) 18px 8px;
  min-height:0;
}
.dslgs-status{
  display:inline-flex; align-items:center; gap:8px; height:30px; padding:0 14px;
  border-radius:999px; font-size:13px; font-weight:600; letter-spacing:.02em;
  color:rgba(246,247,251,.82);
  background:linear-gradient(180deg,rgba(255,255,255,.10),rgba(255,255,255,.04));
  border:1px solid rgba(255,255,255,.13);
  -webkit-backdrop-filter:blur(14px) saturate(150%); backdrop-filter:blur(14px) saturate(150%);
  box-shadow:0 8px 26px rgba(0,0,0,.34), inset 0 1px 0 rgba(255,255,255,.22);
  transition:opacity .3s var(--dslgs-ease);
}
.dslgs-status[hidden]{ display:none; }
.dslgs-dot{
  width:7px; height:7px; border-radius:50%; background:#7fd8ff;
  box-shadow:0 0 10px 2px rgba(127,216,255,.75);
  animation:dslgs-pulse 1.6s var(--dslgs-ease) infinite;
}
.dslgs-root[data-state="listening"] .dslgs-dot{ background:#ff6f9c; box-shadow:0 0 12px 3px rgba(255,111,156,.8); }
.dslgs-root[data-state="thinking"] .dslgs-dot{ background:#ffd479; box-shadow:0 0 12px 3px rgba(255,212,121,.75); }
.dslgs-root[data-state="speaking"] .dslgs-dot{ background:#9d8bff; box-shadow:0 0 12px 3px rgba(157,139,255,.8); }
@keyframes dslgs-pulse{ 0%,100%{ transform:scale(1); opacity:1 } 50%{ transform:scale(.72); opacity:.55 } }

/* ── the crystal ball ────────────────────────────────────────────────── */
.dslgs-orbwrap{
  position:relative; flex:0 0 auto;
  width:var(--dslgs-orb,min(62vmin,440px)); height:var(--dslgs-orb,min(62vmin,440px));
  transition:width .5s var(--dslgs-ease), height .5s var(--dslgs-ease);
  touch-action:manipulation; -webkit-tap-highlight-color:transparent;
}
.dslgs-root[data-state="listening"] .dslgs-orbwrap{ --dslgs-orb:min(66vmin,472px); }
.dslgs-orbwrap canvas{
  position:absolute; inset:0; width:100%; height:100%; display:block; pointer-events:none;
}
.dslgs-fx{
  /* Sized to the ball itself (scale 0.62 of the wrapper ⇒ 19% inset), so the
     internal light never spills past the glass silhouette. */
  inset:19%; border-radius:50%; overflow:hidden;
  mix-blend-mode:screen; opacity:var(--dslgs-fx-alpha,.9);
  transition:opacity .35s var(--dslgs-ease);
  -webkit-mask-image:radial-gradient(circle at 50% 50%, #000 58%, rgba(0,0,0,.35) 82%, transparent 97%);
  mask-image:radial-gradient(circle at 50% 50%, #000 58%, rgba(0,0,0,.35) 82%, transparent 97%);
}
.dslgs-halo{
  position:absolute; inset:-22%; border-radius:50%; pointer-events:none;
  background:radial-gradient(circle at 50% 52%, rgba(150,190,255,.34), rgba(120,90,255,.16) 42%, rgba(0,0,0,0) 68%);
  filter:blur(14px); opacity:calc(.30 + var(--dslgs-level,0) * .75);
  transition:opacity .12s linear;
}
.dslgs-ring{
  position:absolute; inset:0; border-radius:50%; pointer-events:none;
  border:1px solid rgba(190,215,255,.30);
  opacity:0; transform:scale(.86);
}
.dslgs-root[data-state="listening"] .dslgs-ring,
.dslgs-root[data-state="speaking"] .dslgs-ring{
  animation:dslgs-ripple 2.9s var(--dslgs-ease) infinite;
}
.dslgs-root[data-state="listening"] .dslgs-ring.d2,
.dslgs-root[data-state="speaking"] .dslgs-ring.d2{ animation-delay:1.45s; }
@keyframes dslgs-ripple{
  0%{ opacity:.0; transform:scale(.82) }
  18%{ opacity:.45 }
  100%{ opacity:0; transform:scale(1.5) }
}

/* ── caption: transcript + streamed answer ───────────────────────────── */
.dslgs-caption{
  position:relative; z-index:2; width:min(760px,92vw); max-height:34vh; overflow:hidden;
  padding:2px 4px; text-align:center;
  font-size:clamp(17px,2.5vh,25px); line-height:1.42; font-weight:600; letter-spacing:-.012em;
  text-wrap:balance;
  transition:opacity .3s var(--dslgs-ease);
}
.dslgs-caption[hidden]{ display:none; }
.dslgs-caption .dslgs-you{
  display:block; font-size:clamp(13px,1.7vh,16px); font-weight:600;
  letter-spacing:.06em; text-transform:uppercase; opacity:.5; margin-bottom:7px;
}
.dslgs-caption .dslgs-answer{
  display:block; max-height:26vh; overflow:hidden;
  background:linear-gradient(180deg,#ffffff,#dfe6ff 62%,#c9b8ff);
  -webkit-background-clip:text; background-clip:text; color:transparent;
  -webkit-mask-image:linear-gradient(180deg,#000 78%,transparent 100%);
  mask-image:linear-gradient(180deg,#000 78%,transparent 100%);
}
.dslgs-caption .dslgs-caret{
  display:inline-block; width:.5em; height:1.02em; margin-left:.08em; vertical-align:-.16em;
  border-radius:2px; background:linear-gradient(180deg,#fff,#b9c8ff);
  animation:dslgs-blink 1.05s steps(1) infinite;
}
@keyframes dslgs-blink{ 0%,55%{opacity:1} 56%,100%{opacity:0} }

.dslgs-toast{
  position:relative; z-index:3; width:min(620px,90vw); text-align:center;
  font-size:13.5px; line-height:1.5; color:#ffd0d6; opacity:.92;
}
.dslgs-toast[hidden]{ display:none; }

/* ── dock ────────────────────────────────────────────────────────────── */
.dslgs-dock{
  position:relative; z-index:3; flex:0 0 auto;
  display:flex; align-items:center; justify-content:center; gap:14px;
  padding:6px 16px calc(20px + env(safe-area-inset-bottom));
}
.dslgs-glass, .dslgs-mic{
  -webkit-appearance:none; appearance:none; border:1px solid rgba(255,255,255,.16);
  background:linear-gradient(180deg,rgba(255,255,255,.14),rgba(255,255,255,.05));
  -webkit-backdrop-filter:blur(18px) saturate(160%); backdrop-filter:blur(18px) saturate(160%);
  color:#f6f7fb; cursor:pointer; font:inherit;
  box-shadow:0 12px 34px rgba(0,0,0,.40), inset 0 1px 0 rgba(255,255,255,.28);
  transition:transform .22s var(--dslgs-ease), background .22s var(--dslgs-ease), box-shadow .22s var(--dslgs-ease);
}
.dslgs-glass:hover, .dslgs-mic:hover{ background:linear-gradient(180deg,rgba(255,255,255,.2),rgba(255,255,255,.08)); }
.dslgs-glass:active, .dslgs-mic:active{ transform:scale(.94); }
.dslgs-glass{ width:46px; height:46px; border-radius:50%; display:grid; place-items:center; font-size:16px; }
.dslgs-glass[aria-pressed="true"]{
  background:linear-gradient(180deg,rgba(150,190,255,.42),rgba(120,110,255,.24));
  border-color:rgba(200,220,255,.5);
}
.dslgs-mic{
  position:relative; width:76px; height:76px; border-radius:50%; display:grid; place-items:center;
}
.dslgs-mic svg{ width:30px; height:30px; display:block; }
.dslgs-root[data-state="listening"] .dslgs-mic{
  background:linear-gradient(180deg,rgba(255,120,170,.55),rgba(160,90,255,.35));
  border-color:rgba(255,190,220,.55);
  box-shadow:0 0 0 10px rgba(255,120,170,.10), 0 14px 40px rgba(255,90,150,.32), inset 0 1px 0 rgba(255,255,255,.4);
  animation:dslgs-breathe 2.1s var(--dslgs-ease) infinite;
}
.dslgs-root[data-state="thinking"] .dslgs-mic{ opacity:.72; }
@keyframes dslgs-breathe{ 0%,100%{ transform:scale(1) } 50%{ transform:scale(1.055) } }

/* ── text fallback bar ───────────────────────────────────────────────── */
.dslgs-type{
  position:relative; z-index:3; display:flex; align-items:center; gap:10px;
  width:min(640px,92vw); margin:0 auto calc(6px + env(safe-area-inset-bottom));
  padding:8px 8px 8px 18px; border-radius:999px;
  background:linear-gradient(180deg,rgba(255,255,255,.10),rgba(255,255,255,.045));
  border:1px solid rgba(255,255,255,.14);
  -webkit-backdrop-filter:blur(20px) saturate(150%); backdrop-filter:blur(20px) saturate(150%);
  box-shadow:0 14px 40px rgba(0,0,0,.36), inset 0 1px 0 rgba(255,255,255,.2);
  opacity:0; pointer-events:none; transform:translateY(8px);
  transition:opacity .3s var(--dslgs-ease), transform .3s var(--dslgs-ease);
}
.dslgs-root[data-typing="true"] .dslgs-type{ opacity:1; pointer-events:auto; transform:none; }
.dslgs-type input{
  flex:1 1 auto; min-width:0; background:none; border:0; outline:none; color:#f6f7fb;
  font:inherit; font-size:15.5px; padding:8px 0;
}
.dslgs-type input::placeholder{ color:rgba(246,247,251,.42); }
.dslgs-send{
  flex:0 0 auto; border:1px solid rgba(255,255,255,.18); cursor:pointer;
  background:linear-gradient(180deg,rgba(180,205,255,.55),rgba(120,130,255,.35));
  color:#080b14; font-weight:700; font-size:14px; border-radius:999px; padding:9px 18px;
}
.dslgs-send:active{ transform:scale(.96); }

/* ── floating launcher (closed state) ────────────────────────────────── */
.dslgs-launcher{
  position:fixed; right:calc(18px + env(safe-area-inset-right)); bottom:calc(88px + env(safe-area-inset-bottom));
  z-index:2147481500; width:58px; height:58px; border-radius:50%; cursor:pointer;
  pointer-events:auto;   /* the root is hit-test transparent by default */
  border:1px solid rgba(255,255,255,.22); padding:0;
  background:
    radial-gradient(circle at 34% 28%, rgba(255,255,255,.95), rgba(255,255,255,.28) 18%, rgba(0,0,0,0) 42%),
    conic-gradient(from 210deg, #6f8dff, #b06bff, #ff6fae, #ffb86b, #6fe3ff, #6f8dff);
  box-shadow:0 10px 30px rgba(0,0,0,.42), inset 0 1px 2px rgba(255,255,255,.6);
  filter:saturate(1.15);
  transition:transform .25s var(--dslgs-ease), box-shadow .25s var(--dslgs-ease), opacity .25s var(--dslgs-ease);
  animation:dslgs-float 5.2s ease-in-out infinite;
  -webkit-tap-highlight-color:transparent;
}
.dslgs-launcher::after{
  content:""; position:absolute; inset:16%; border-radius:50%; pointer-events:none;
  background:radial-gradient(circle at 50% 62%, rgba(8,10,20,.62), rgba(8,10,20,.18) 60%, rgba(0,0,0,0) 72%);
  mix-blend-mode:multiply;
}
.dslgs-launcher:hover{ transform:scale(1.06); box-shadow:0 14px 38px rgba(0,0,0,.5), inset 0 1px 2px rgba(255,255,255,.7); }
.dslgs-launcher:active{ transform:scale(.95); }
.dslgs-root[data-open="true"] .dslgs-launcher{ opacity:0; pointer-events:none; }
@keyframes dslgs-float{ 0%,100%{ transform:translateY(0) } 50%{ transform:translateY(-5px) } }

.dslgs-close{
  position:absolute; top:calc(12px + env(safe-area-inset-top)); right:calc(14px + env(safe-area-inset-right));
  z-index:4; width:40px; height:40px; border-radius:50%; display:grid; place-items:center;
  border:1px solid rgba(255,255,255,.14); background:rgba(255,255,255,.07); color:#f6f7fb;
  font-size:19px; line-height:1; cursor:pointer;
  -webkit-backdrop-filter:blur(14px); backdrop-filter:blur(14px);
}
.dslgs-close:active{ transform:scale(.94); }

@media (max-width:430px){
  .dslgs-stage{ gap:10px; padding-top:max(18px,env(safe-area-inset-top)); }
  .dslgs-dock{ gap:10px; padding-bottom:calc(12px + env(safe-area-inset-bottom)); }
  .dslgs-mic{ width:66px; height:66px; }
}
@media (max-height:560px){
  .dslgs-caption{ font-size:15px; max-height:24vh; }
  .dslgs-status{ display:none; }
}
@media (prefers-reduced-motion:reduce){
  .dslgs-launcher,.dslgs-dot,.dslgs-root[data-state="listening"] .dslgs-mic{ animation:none; }
  .dslgs-ring{ display:none; }
}
`;

    // ─────────────────────────────────────────────────────────────────────
    // GLSL. Three programs share one vertex shader and one full-screen
    // triangle: the liquid-glass crystal ball (with alpha outside the ball),
    // the Siri wave core, and the Siri fluid-dots core. The two light-effect
    // programs render black-background emissive images that are composited
    // over the ball with `mix-blend-mode: screen`, which is why they can stay
    // near-verbatim ports of the reference shaders in this repository.
    // ─────────────────────────────────────────────────────────────────────
    var VERT_SRC = `
attribute vec2 aPos;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }
`;

    var ORB_FRAG = `
precision highp float;
uniform vec2  iResolution;
uniform float iTime;
uniform float uLevel;      // 0..1 live audio level (mic or synthesised)
uniform float uEnergy;     // 0..1 overall engagement
uniform float uScale;      // ball radius, in half-short-side units
uniform float uRefraction; // rim displacement
uniform float uBand;       // refracting rim width, normalized orb units
uniform float uDome;       // interior magnification
uniform float uAberration; // chromatic dispersion
uniform float uTopDarken;  // upper-cap mirror amount
uniform float uBlack0;     // mirror window lower edge (normalized orb y)
uniform float uBlack1;     // mirror window upper edge
uniform float uEdgeBleed;  // rim / caustic gain
uniform float uBrightness;

const float PI  = 3.14159265359;
const float TAU = 6.28318530718;

float hash21(vec2 p){
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}
float vnoise(vec2 p){
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = hash21(i);
  float b = hash21(i + vec2(1.0, 0.0));
  float c = hash21(i + vec2(0.0, 1.0));
  float d = hash21(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
// Low-octave fbm: this shader is sampled 3x per pixel for dispersion, so the
// octave count is the main cost lever on phone GPUs.
float fbm(vec2 p, int oct){
  float s = 0.0, a = 0.55;
  for(int i = 0; i < 3; i++){
    if(i >= oct) break;
    s += a * vnoise(p);
    p = p * 2.07 + vec2(11.7, 5.3);
    a *= 0.5;
  }
  return s;
}
// Cosine palette tuned to the Siri spectrum: deep indigo, violet, magenta,
// warm amber and a cyan highlight, with no hard stops anywhere.
vec3 siriPalette(float t){
  return vec3(0.52, 0.44, 0.60)
       + vec3(0.46, 0.40, 0.44) * cos(6.28318 * (vec3(1.0) * t + vec3(0.00, 0.24, 0.50)));
}

// Domain-warped scalar field: the "liquid" living inside the glass. Sampled
// once per pixel and reused by all three dispersion channels.
float warpField(vec2 p, float t){
  float w1 = fbm(p * 1.55 + vec2(t * 0.11, -t * 0.085), 2);
  float w2 = fbm(p * 2.10 - vec2(t * 0.095, t * 0.13) + w1 * 0.85, 2);
  vec2 q = p + (vec2(w1, w2) - 0.5) * 0.72;
  float base = fbm(q * 1.35 + vec2(t * 0.052, -t * 0.043), 3);
  float fine = fbm(q * 3.60 - vec2(t * 0.070, t * 0.050), 2);
  return base * 0.78 + fine * 0.22;
}

// Per-channel colouring of a warped sample. The hue sweep is a *directional*
// gradient rather than atan(): atan() jumps by TAU across the negative x axis,
// which would print a hard seam straight through the middle of the ball.
vec3 liquid(vec2 p, float v, float t, float e, float chan){
  float r = length(p);
  float sweep = -0.60 * p.y + 0.34 * p.x;
  float hue = fract(0.54 + 0.40 * sweep + 0.48 * (v - 0.5)
                    + 0.13 * r + 0.022 * t + 0.045 * chan);
  vec3 c = siriPalette(hue);
  // Cosine palettes are pastel; push saturation so the glass reads as the
  // vivid Siri spectrum rather than a milky bubble.
  float lum = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(vec3(lum), c, 1.7);
  // Deeper body: a dark centre, a saturated belt, a hot rim.
  float core = exp(-r * r * 3.1);
  c = mix(c, vec3(1.0, 0.975, 0.95), core * 0.30);
  c *= 0.18 + 1.35 * smoothstep(0.08, 0.92, r);
  c *= 0.55 + 0.80 * e;
  return c;
}

void main(){
  vec2  R  = iResolution.xy;
  vec2  fc = gl_FragCoord.xy;
  float mn = min(R.x, R.y);
  float px = 2.0 / mn;
  float t  = iTime;
  float lv = clamp(uLevel, 0.0, 1.0);
  float e  = clamp(uEnergy, 0.0, 1.0);

  vec2  q = (fc - 0.5 * R) / (0.5 * mn);            // y up; 1.0 = half short side
  float radius = uScale * (1.0 + 0.013 * sin(t * 0.85) + 0.050 * lv + 0.028 * e);
  float rq = length(q);
  float rr = rq / max(radius, 1e-4);                 // 0 centre, 1 rim
  float sd = rr - 1.0;

  float aa     = 1.7 * px / max(radius, 1e-4);
  float inside = smoothstep(aa, -aa, sd);
  float halo   = exp(-max(sd, 0.0) * 11.0) * (0.10 + 0.42 * lv + 0.26 * e)
               + exp(-max(sd, 0.0) * 3.2) * 0.05;
  if(inside <= 0.002 && halo <= 0.004){ gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0); return; }

  vec3 col = vec3(0.0);
  if(inside > 0.002){
    // ── sphere geometry: a real surface normal makes every shading term
    //    smooth, which is what keeps the ball free of seams ──
    float rc = min(rr, 1.0);
    float z  = sqrt(max(0.0, 1.0 - rc * rc));
    vec3  nrm = vec3(q / max(radius, 1e-4), z);
    vec2  n2  = q / max(rq, 1e-4);

    // ── lens displacement of the interior field ──
    float x = clamp(1.0 - (-min(sd, 0.0)) / max(uBand, 1e-4), 0.0, 1.0);
    float lens = 1.0 - sqrt(max(1.0 - x * x, 0.0));
    vec2  sp = (q / max(radius, 1e-4)) * (1.0 - uDome * 0.16) - n2 * lens * uRefraction;

    float v  = warpField(sp, t);
    float ab = uAberration * (0.30 + 1.35 * abs(n2.x * n2.y)) * (1.0 + 0.85 * lv);
    col.r = liquid(sp + n2 * ab, v, t, e,  1.0).r;
    col.g = liquid(sp,           v, t, e,  0.0).g;
    col.b = liquid(sp - n2 * ab, v, t, e, -1.0).b;

    // ── key light, specular, fresnel ──
    vec3  Ldir = normalize(vec3(-0.42, 0.60, 0.68));
    float diff = clamp(dot(nrm, Ldir), 0.0, 1.0);
    float sharp = pow(clamp(dot(reflect(-Ldir, nrm), vec3(0.0, 0.0, 1.0)), 0.0, 1.0), 44.0);
    float sheen = pow(clamp(dot(reflect(-Ldir, nrm), vec3(0.0, 0.0, 1.0)), 0.0, 1.0), 5.0);
    float spec = sharp + sheen * 0.10;
    float fres = pow(1.0 - z, 2.3);

    vec3 body = col * (0.40 + 0.95 * diff);
    body += vec3(0.95, 0.97, 1.0) * spec * (0.42 + 0.30 * e);

    // ── liquid-glass mirror: the upper cap reflects a dark environment ──
    float mirror = smoothstep(uBlack0, uBlack1, nrm.y);
    mirror = mirror * mirror * (3.0 - 2.0 * mirror);
    vec3 glass = mix(vec3(0.012, 0.016, 0.032), vec3(0.05, 0.08, 0.16), diff * 0.55);
    col = mix(body, glass, clamp(mirror * uTopDarken, 0.0, 1.0));

    // ── caustic pool under the sphere: warm light pooling at the bottom rim ──
    float pool = pow(clamp(-nrm.y, 0.0, 1.0), 2.1) * smoothstep(0.15, 0.95, rr);
    vec3 poolCol = mix(vec3(1.0, 0.58, 0.30), vec3(0.65, 0.55, 1.0),
                       0.5 + 0.5 * sin(t * 0.21));
    col += poolCol * pool * uEdgeBleed * (0.6 + 0.5 * e);

    // ── glass rim ──
    col += vec3(0.72, 0.82, 1.0) * fres * (0.10 + 0.26 * lv) * uEdgeBleed * 2.6;

    // ── shell line, brightest along the lower edge ──
    float shell = smoothstep(0.030, 0.0, abs(sd + 0.022));
    col += vec3(0.16, 0.18, 0.22) * shell * (0.45 + 0.55 * smoothstep(0.6, -0.9, nrm.y));
  }

  // ── halo just outside the silhouette ──
  vec3 haloCol = mix(vec3(0.42, 0.60, 1.0), vec3(1.0, 0.62, 0.88), 0.5 + 0.5 * sin(t * 0.27));
  vec3 outc = haloCol * halo;

  vec3 rgb = mix(outc, col, inside) * uBrightness;
  float alpha = clamp(max(inside, halo * 0.95), 0.0, 1.0);
  gl_FragColor = vec4(rgb * alpha, alpha);
}
`;

    // Siri wave core (siriWaveCore): spectral dispersion, Lorentzian glow.
    var WAVE_FRAG = `
precision highp float;
uniform vec2  iResolution;
uniform float iTime;
uniform float uLevel;
uniform float uOpacity;
uniform float uZoom;

const float PI = 3.14159265359;

vec3 spectral4(int s){
  float x = float(s);
  return clamp(vec3(abs(x - 3.0) - 1.0, 2.0 - abs(x - 2.0), 2.0 - abs(x - 4.0)), 0.0, 1.0);
}
vec3 waveCol(vec2 p, float t, float spread, float amp){
  float low  = clamp(0.45 + 0.45 * sin(t * 0.8) * sin(t * 0.37 + 1.0), 0.0, 1.0);
  float mid  = clamp(0.40 + 0.40 * sin(t * 1.7 + 2.0) * sin(t * 0.53), 0.0, 1.0);
  float high = clamp(0.30 + 0.30 * sin(t * 2.9 + 4.0) * sin(t * 0.71 + 2.0), 0.0, 1.0);
  float drift = mod(t, 20.0 * PI) * 2.4;
  float env = cos(PI * 0.5 * min(abs(0.9 * p.x), 1.0)); env *= env;
  float A1 = (0.30 + 0.01 * low * 6.0) * amp;
  float A2 = A1 + (mid * 0.05 + high * 0.06) * amp;
  float AB = 2.6 + mid * 0.8 + high * 0.5;
  float th = 0.030 * spread;
  float inten = 0.01 * (2.0 + low * 1.5) * (0.55 + 1.05 * amp);
  float soft = 0.01 * max(0.0, 2.5 + mid * 0.4) * spread;
  float yMain = A1 * env * sin(p.x * 1.1 + drift);
  float bandAmt = 1e-4 * 9000.0 * inten;
  vec3 num = vec3(0.0), den = vec3(0.0);
  for(int s = 0; s < 4; s++){
    vec3 hue = spectral4(s); den += hue;
    float ab = mix(-AB, AB, float(s) / 3.0);
    float yL = A2 * env * sin(p.x + drift + ab);
    float d = abs(p.y - yL);
    float line = inten / (sqrt(d * d + soft * soft) + th);
    float lo = min(yMain, yL), hi = max(yMain, yL);
    float dB = max(0.0, max(p.y - hi, lo - p.y));
    float band = bandAmt / (dB + 0.16);
    num += hue * (line + band);
  }
  vec3 col = num / den;
  float dM = abs(p.y - yMain);
  col += 0.5 * inten / (sqrt(dM * dM + soft * soft) + th);
  col = pow(max(col, 0.0), vec3(1.5));
  return col * exp(-pow(p.x * 1.2, 2.0));
}
void main(){
  vec2 R = iResolution.xy;
  vec2 p = (2.0 * gl_FragCoord.xy - R) / min(R.x, R.y);
  p /= max(uZoom, 0.05);
  float lv = clamp(uLevel, 0.0, 1.0);
  vec3 col = waveCol(p * vec2(1.0, 1.5), iTime, 7.0, 0.85 + 0.75 * lv)
           + waveCol(p * vec2(1.0, 1.5), iTime, 1.0, 0.85 + 0.75 * lv) * 0.35;
  col *= uOpacity * (0.55 + 0.85 * lv);
  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
`;

    // Siri fluid-dots core (siriFluidDotsCore): metaball SDF + smooth-min.
    var DOTS_FRAG = `
precision highp float;
uniform vec2  iResolution;
uniform float iTime;
uniform float uLevel;
uniform float uOpacity;
uniform float uZoom;

const float TAU = 6.28318530718;
const int   N   = 6;
const float SMOOTH_K   = 0.09;
const float INTENSITY  = 0.0030;
const float FALLOFF_P  = 1.35;
const float FADE_START = 0.02;
const float FADE_END   = 0.58;
const float HUE_SPEED  = 0.06;
const float HUE_SPAN   = 0.667;
const float MERGE_PERIOD = 6.0;
const float T_MOVE   = 1.25;
const float STAGGER  = 0.33;
const float W = 4.6;
const float L = 3.2;
const float PIERCE  = 0.12;
const float RECOIL  = 0.035;
const float REC_LAG = 0.11;
const float GATHER_PERIOD = 12.0;
const float GATHER_START  = 9.2;
const float GATHER_HOLD   = 0.8;
const float GATHER_R      = 0.008;
const float GATHER_DIM    = 0.85;
const float GATHER_IN     = 1.8;
const float GATHER_IN_L   = 7.5;
const float BURST_W = 6.5;
const float BURST_L = 4.0;

float hash11(float n){ return fract(sin(n * 127.1 + 311.7) * 43758.5453); }
float settleWL(float tau, float w, float l){
  if(tau <= 0.0) return 0.0;
  return 1.0 - exp(-l * tau) * cos(w * tau);
}
float settle(float tau){ return settleWL(tau, W, L); }
float settleCrit(float tau, float l){
  if(tau <= 0.0) return 0.0;
  return 1.0 - exp(-l * tau) * (1.0 + l * tau);
}
float smin(float a, float b, float k){
  float h = max(k - abs(a - b), 0.0) / k;
  return min(a, b) - h * h * k * 0.25;
}
vec3 hue2rgb(float h){
  h = fract(h);
  float r = clamp(abs(h * 6.0 - 3.0) - 1.0, 0.0, 1.0);
  float g = clamp(2.0 - abs(h * 6.0 - 2.0), 0.0, 1.0);
  float b = clamp(2.0 - abs(h * 6.0 - 4.0), 0.0, 1.0);
  return vec3(r, g, b);
}
float dotR(float fi, float seed, float t){
  return 0.036 + 0.010 * sin(t * 1.3 + seed * TAU) + 0.005 * sin(t * 2.4 + fi * 1.3);
}
float dotSD(vec2 p, vec2 pos, float r, float t, float fi, float shapeDamp){
  vec2 d = p - pos;
  float sq = 0.075 * (0.5 + 0.5 * sin(t * 0.9 + fi * 2.0)) * shapeDamp;
  float ca = cos(t * 0.35 + fi), sa = sin(t * 0.35 + fi);
  d = mat2(ca, -sa, sa, ca) * d;
  d *= vec2(1.0 + sq, 1.0 - sq);
  return length(d) - r;
}
vec3 scene(vec2 p, float t, float lv){
  float k = floor(t / MERGE_PERIOD);
  float u = fract(t / MERGE_PERIOD);
  float te = u * MERGE_PERIOD;
  float tg = mod(t, GATHER_PERIOD);
  float g = settleCrit((tg - GATHER_START) * GATHER_IN, GATHER_IN_L)
          - settleWL(tg - GATHER_START - GATHER_HOLD, BURST_W, BURST_L);
  float gC = clamp(g, 0.0, 1.0);
  float gBright = mix(1.0, GATHER_DIM, gC);
  vec3  total3 = vec3(1e5);
  vec3  cAcc = vec3(0.0);
  float wAcc = 1e-6;
  for(int i = 0; i < N; i++){
    float fi = float(i);
    float seed = hash11(fi);
    float ang = fi / float(N) * TAU + t * 0.35;
    vec2 dir = vec2(cos(ang), sin(ang));
    float Rr = 0.17 + 0.010 * sin(t * 1.0) + 0.007 * sin(t * 1.3 + seed * TAU);
    float pairId = mod(fi, 3.0);
    float moverLow = mod(k + pairId, 2.0);
    float isMover = (fi < 2.5) ? step(moverLow, 0.5) : step(0.5, moverLow);
    float goStart = pairId * STAGGER;
    float retStart = 3.0 * STAGGER + pairId * STAGGER;
    float m   = (settle(te - goStart) - settle(te - retStart)) * isMover;
    float rec = (settle(te - goStart - REC_LAG) - settle(te - retStart - REC_LAG)) * (1.0 - isMover);
    float rSelf = dotR(fi, seed, t);
    rSelf = mix(rSelf, 0.036, gC) * (1.0 + 0.55 * lv);
    float fj = mod(fi + 3.0, 6.0);
    float rPart = dotR(fj, hash11(fj), t);
    float deep = -(Rr + RECOIL) - PIERCE * rPart;
    float radial = mix(Rr, deep, m) + RECOIL * rec;
    radial = mix(radial, GATHER_R, g);
    vec2 pos = radial * dir;
    float sdR = dotSD(p, pos, rSelf, t, fi, 1.0 - gC);
    float sdG = dotSD(p, pos, rSelf, t, fi, 1.0 - gC);
    float sdB = dotSD(p, pos, rSelf, t, fi, 1.0 - gC);
    total3 = vec3(smin(total3.r, sdR, SMOOTH_K),
                  smin(total3.g, sdG, SMOOTH_K),
                  smin(total3.b, sdB, SMOOTH_K));
    float hue = fract(fi / float(N) + t * HUE_SPEED) * HUE_SPAN;
    vec3 dotCol = mix(vec3(1.0), hue2rgb(hue), 0.30);
    float w = exp(-sdG * 0.5);
    cAcc += w * dotCol;
    wAcc += w;
  }
  vec3 sd3 = max(total3, vec3(0.0)) + 1e-4;
  vec3 core3 = clamp(INTENSITY * (1.0 + 1.6 * lv) / pow(sd3, vec3(FALLOFF_P)), 0.0, 1.0);
  vec3 edge3 = 1.0 - smoothstep(vec3(FADE_START), vec3(FADE_END), sd3);
  return core3 * edge3 * gBright * (cAcc / wAcc);
}
void main(){
  vec2 R = iResolution.xy;
  vec2 p = (2.0 * gl_FragCoord.xy - R) / min(R.x, R.y);
  p /= max(uZoom, 0.05);
  float t = iTime;
  float lv = clamp(uLevel, 0.0, 1.0);
  p /= 1.0 + 0.03 * sin(t * 1.0);
  vec3 col = scene(p, t, lv);
  col *= 1.0 + 0.05 * sin(t * 1.0 + 1.0);
  col = pow(max(col, 0.0), vec3(1.0 / 1.2));
  gl_FragColor = vec4(clamp(col, 0.0, 1.0) * uOpacity, 1.0);
}
`;

    // ─────────────────────────────────────────────────────────────────────
    // WebGL plumbing: one full-screen triangle per program, no mesh, no
    // draw-call overhead — the same shape the reference demos in this
    // repository use.
    // ─────────────────────────────────────────────────────────────────────
    var QUAD = new Float32Array([-1, -1, 3, -1, -1, 3]);
    var ORB_UNIFORMS = [
      "iResolution", "iTime", "uLevel", "uEnergy", "uScale", "uRefraction",
      "uBand", "uDome", "uAberration", "uTopDarken", "uBlack0", "uBlack1",
      "uEdgeBleed", "uBrightness",
    ];
    var FX_UNIFORMS = ["iResolution", "iTime", "uLevel", "uOpacity", "uZoom"];

    function compile(gl, type, src) {
      var sh = gl.createShader(type);
      gl.shaderSource(sh, src);
      gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        var log = gl.getShaderInfoLog(sh);
        gl.deleteShader(sh);
        throw new Error("shader compile failed: " + log);
      }
      return sh;
    }

    /** One canvas + one fragment program, drawn as a full-screen triangle. */
    function GlProgram(canvas, fragSrc, names) {
      var opts = {
        alpha: true,
        premultipliedAlpha: true,
        antialias: false,
        depth: false,
        stencil: false,
        preserveDrawingBuffer: false,
        powerPreference: "high-performance",
      };
      var gl = canvas.getContext("webgl", opts) || canvas.getContext("experimental-webgl", opts);
      if (!gl) throw new Error("webgl unavailable");
      var prog = gl.createProgram();
      gl.attachShader(prog, compile(gl, gl.VERTEX_SHADER, VERT_SRC));
      gl.attachShader(prog, compile(gl, gl.FRAGMENT_SHADER, fragSrc));
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
        throw new Error("program link failed: " + gl.getProgramInfoLog(prog));
      }
      gl.useProgram(prog);
      var buf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, QUAD, gl.STATIC_DRAW);
      var aPos = gl.getAttribLocation(prog, "aPos");
      gl.enableVertexAttribArray(aPos);
      gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);
      var U = {};
      for (var i = 0; i < names.length; i++) U[names[i]] = gl.getUniformLocation(prog, names[i]);
      gl.clearColor(0, 0, 0, 0);

      this.gl = gl;
      this.canvas = canvas;
      this.U = U;

      /** Size the drawing buffer from the element box at a reduced scale. */
      this.resize = function (scale) {
        var dpr = Math.min(window.devicePixelRatio || 1, 2);
        var w = Math.max(1, Math.round((canvas.clientWidth || 1) * scale * dpr));
        var h = Math.max(1, Math.round((canvas.clientHeight || 1) * scale * dpr));
        if (canvas.width !== w || canvas.height !== h) {
          canvas.width = w;
          canvas.height = h;
        }
        gl.viewport(0, 0, canvas.width, canvas.height);
      };

      this.draw = function (t, values) {
        gl.useProgram(prog);
        gl.uniform2f(U.iResolution, canvas.width, canvas.height);
        gl.uniform1f(U.iTime, t);
        for (var k in values) {
          if (U[k]) gl.uniform1f(U[k], values[k]);
        }
        gl.clear(gl.COLOR_BUFFER_BIT);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      };

      this.dispose = function () {
        var lose = gl.getExtension("WEBGL_lose_context");
        if (lose) lose.loseContext();
      };
    }

    /**
     * Owns both canvases and the render loop. Quality degrades automatically
     * when frames get expensive, so the surface stays smooth on weak phones.
     */
    function Renderer(nodes, params, onFxKind) {
      var orb = new GlProgram(nodes.orb, ORB_FRAG, ORB_UNIFORMS);
      var fxPrograms = {};
      var fxKind = "wave";
      var currentFx = null;
      var quality = 1;
      var scale = params.renderScale;
      var energy = 0;
      var level = 0;
      var target = 0;
      var mode = "idle";
      var raf = 0;
      var disposed = false;
      var last = 0;
      var slowFrames = 0;
      var fastFrames = 0;
      var captureCb = null;

      function fx(kind) {
        if (fxPrograms[kind]) return fxPrograms[kind];
        var src = kind === "dots" ? DOTS_FRAG : WAVE_FRAG;
        fxPrograms[kind] = new GlProgram(nodes.fx, src, FX_UNIFORMS);
        return fxPrograms[kind];
      }

      try {
        currentFx = fx("wave");
      } catch (e) {
        currentFx = null;
      }

      function frame(now) {
        if (disposed) return;
        raf = window.requestAnimationFrame(frame);
        var t = now / 1000;
        var dt = last ? Math.min(0.1, (now - last) / 1000) : 0.016;
        last = now;

        level += (target - level) * Math.min(1, dt * 7.5);
        var want = mode === "listening" ? 0.92 : mode === "thinking" ? 0.75 : mode === "speaking" ? 0.95 : 0.18;
        energy += (want - energy) * Math.min(1, dt * 1.9);

        try {
          orb.resize(scale);
          orb.draw(t, {
            uLevel: level,
            uEnergy: energy,
            uScale: params.scale,
            uRefraction: params.refraction,
            uBand: params.band,
            uDome: params.dome,
            uAberration: params.aberration,
            uTopDarken: params.topDarken,
            uBlack0: params.black0,
            uBlack1: params.black1,
            uEdgeBleed: params.edgeBleed,
            uBrightness: params.brightness,
          });
          if (currentFx) {
            currentFx.resize(scale);
            currentFx.draw(t, {
              uLevel: level,
              uOpacity: params.fx * 0.62 * (mode === "idle" ? 0.40 : 1),
              uZoom: mode === "idle" ? 1.35 : 1.0,
            });
          }
        } catch (e) {
          disposed = true;
          return;
        }

        // One-shot readback hook: the drawing buffer is only guaranteed valid
        // inside the frame that produced it (preserveDrawingBuffer is off), so
        // captures run here, immediately after both draws.
        if (captureCb) {
          var cb = captureCb;
          captureCb = null;
          try { cb(); } catch (e) {}
        }

        if (dt > 0.034) {
          slowFrames++;
          fastFrames = 0;
        } else if (dt < 0.021) {
          fastFrames++;
          slowFrames = 0;
        }
        if (slowFrames > 45 && quality > 0.55) {
          quality = 0.55;
          scale = params.renderScale * quality;
          slowFrames = 0;
        } else if (slowFrames > 45 && currentFx && mode === "idle") {
          currentFx = null;
          nodes.fx.style.display = "none";
          slowFrames = 0;
        } else if (fastFrames > 240 && quality < 1) {
          quality = 1;
          scale = params.renderScale;
          fastFrames = 0;
        }
      }

      return {
        /** Run `cb` right after the next frame's draws, while the buffer is valid. */
        capture: function (cb) { captureCb = cb; },
        start: function () {
          if (!raf) {
            last = 0;
            raf = window.requestAnimationFrame(frame);
          }
        },
        stop: function () {
          if (raf) window.cancelAnimationFrame(raf);
          raf = 0;
        },
        setMode: function (next) {
          mode = next;
        },
        /** Live microphone / synthesis level, 0..1. */
        setLevel: function (v) {
          target = Math.max(0, Math.min(1, v));
        },
        setFx: function (kind) {          if (kind !== fxKind) {
            try {
              currentFx = fx(kind);
              fxKind = kind;
              nodes.fx.style.display = "";
            } catch (e) {
              /* keep the previous effect if this program cannot build */
            }
          }
          if (onFxKind) onFxKind(fxKind);
        },
        getFx: function () {
          return fxKind;
        },
        dispose: function () {
          disposed = true;
          if (raf) window.cancelAnimationFrame(raf);
          raf = 0;
          try { orb.dispose(); } catch (e) {}
          for (var k in fxPrograms) {
            try { fxPrograms[k].dispose(); } catch (e) {}
          }
        },
      };
    }

    // ─────────────────────────────────────────────────────────────────────
    // Microphone capture → 16 kHz mono PCM16 WAV → base64.
    //
    // The stock DSH voice plugin sends exactly this payload to the host
    // `speech.transcribe` Remote (PCM16 WAV, base64, inside the JSON RPC
    // carrier), so a compatible payload is all that is needed to reuse the
    // profile's configured speech-to-text provider (SenseVoice, local).
    // ─────────────────────────────────────────────────────────────────────
    function pickMime() {
      if (typeof window.MediaRecorder === "undefined") return null;
      var candidates = [
        "audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus",
        "audio/mp4", "audio/mpeg",
      ];
      if (typeof MediaRecorder.isTypeSupported !== "function") return "";
      for (var i = 0; i < candidates.length; i++) {
        if (MediaRecorder.isTypeSupported(candidates[i])) return candidates[i];
      }
      return "";
    }

    function encodeWav(samples, sampleRate) {
      var len = samples.length;
      var ab = new ArrayBuffer(44 + len * 2);
      var v = new DataView(ab);
      function ascii(off, s) { for (var i = 0; i < s.length; i++) v.setUint8(off + i, s.charCodeAt(i)); }
      ascii(0, "RIFF"); v.setUint32(4, 36 + len * 2, true); ascii(8, "WAVE");
      ascii(12, "fmt "); v.setUint32(16, 16, true);
      v.setUint16(20, 1, true); v.setUint16(22, 1, true);
      v.setUint32(24, sampleRate, true); v.setUint32(28, sampleRate * 2, true);
      v.setUint16(32, 2, true); v.setUint16(34, 16, true);
      ascii(36, "data"); v.setUint32(40, len * 2, true);
      var o = 44;
      for (var i = 0; i < len; i++) {
        var s = samples[i];
        s = s < -1 ? -1 : s > 1 ? 1 : s;
        v.setInt16(o, s < 0 ? s * 0x8000 : s * 0x7fff, true);
        o += 2;
      }
      return new Uint8Array(ab);
    }

    function toBase64(bytes) {
      var out = "";
      var CH = 0x8000;
      for (var i = 0; i < bytes.length; i += CH) {
        out += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + CH, bytes.length)));
      }
      return window.btoa(out);
    }

    /**
     * One shared AudioContext, created and resumed inside a user gesture.
     * Browsers' autoplay policies refuse to start a context that was not
     * created from a gesture, and the hands-free path starts capture ~400 ms
     * after the tap that opened the surface — priming on every pointer-down
     * keeps that first capture audible to the recogniser.
     */
    var sharedAudio = null;
    function primeAudio() {
      try {
        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return null;
        if (!sharedAudio || sharedAudio.state === "closed") sharedAudio = new AC();
        if (sharedAudio.state === "suspended" && sharedAudio.resume) sharedAudio.resume();
        return sharedAudio;
      } catch (e) {
        return null;
      }
    }

    /**
     * One microphone acquisition. Mirrors the stock plugin's behavior:
     * MediaRecorder for the signal, an AnalyserNode for the live level, then
     * decode + resample to the 16 kHz mono the host provider expects.
     */
    function Recorder() {
      var stream = null, ac = null, analyser = null, recorder = null;
      var chunks = [];
      var levelBuf = null;
      var levelTimer = 0;
      var level = 0;
      var stopping = null;

      function release() {
        if (levelTimer) { window.clearInterval(levelTimer); levelTimer = 0; }
        if (stream) {
          var tracks = stream.getTracks();
          for (var i = 0; i < tracks.length; i++) { try { tracks[i].stop(); } catch (e) {} }
          stream = null;
        }
        // The shared AudioContext deliberately outlives one recording: closing
        // it would require another gesture before the next capture.
        analyser = null;
        level = 0;
      }

      function readLevel() {
        if (!analyser || !levelBuf) return 0;
        analyser.getByteTimeDomainData(levelBuf);
        var sum = 0;
        for (var i = 0; i < levelBuf.length; i++) {
          var d = (levelBuf[i] - 128) / 128;
          sum += d * d;
        }
        var rms = Math.sqrt(sum / levelBuf.length);
        // Perceptual curve: speech sits around 0.02..0.25 RMS.
        return Math.max(0, Math.min(1, Math.pow(rms * 4.2, 0.75)));
      }

      return {
        get level() { return level; },
        get active() { return recorder !== null; },

        start: function () {
          if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            return Promise.reject(new Error("microphone-unavailable"));
          }
          return navigator.mediaDevices.getUserMedia({
            audio: {
              channelCount: 1,
              echoCancellation: true,
              noiseSuppression: true,
              autoGainControl: true,
            },
          }).then(function (s) {
            stream = s;
            ac = primeAudio();
            if (!ac) throw new Error("audio-context-unavailable");
            if (ac.state === "suspended" && ac.resume) ac.resume();
            var src = ac.createMediaStreamSource(s);
            analyser = ac.createAnalyser();
            analyser.fftSize = 1024;
            src.connect(analyser);
            levelBuf = new Uint8Array(analyser.fftSize);
            levelTimer = window.setInterval(function () {
              var next = readLevel();
              level = level + (next - level) * 0.4;
            }, 60);

            chunks = [];
            var mime = pickMime();
            recorder = mime === null
              ? new window.MediaRecorder(s)
              : mime ? new window.MediaRecorder(s, { mimeType: mime }) : new window.MediaRecorder(s);
            recorder.ondataavailable = function (e) {
              if (e.data && e.data.size > 0) chunks.push(e.data);
            };
            recorder.start(250);
            return true;
          });
        },

        /** Stop, decode and resample; resolves the base64 PCM16 WAV payload. */
        stop: function () {
          if (!recorder) return Promise.resolve(null);
          if (stopping) return stopping;
          var rec = recorder;
          var ctxForDecode = ac;
          stopping = new Promise(function (resolve) {
            rec.onstop = function () {
              var type = rec.mimeType || "audio/webm";
              var blob = new Blob(chunks, { type: type });
              chunks = [];
              recorder = null;
              resolve({ blob: blob, ac: ctxForDecode });
            };
            try { rec.stop(); } catch (e) { resolve({ blob: null, ac: ctxForDecode }); }
          }).then(function (out) {
            stopping = null;
            if (!out.blob || out.blob.size < 512) { release(); return null; }
            return out.blob.arrayBuffer().then(function (ab) {
              var ctx = out.ac;
              return new Promise(function (res, rej) {
                var p = ctx.decodeAudioData(ab, res, rej);
                if (p && p.then) p.then(res, rej);
              });
            }).then(function (audioBuf) {
              var target = 16000;
              var frames = Math.max(1, Math.ceil(audioBuf.duration * target));
              var OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
              if (!OAC) throw new Error("offline-audio-unavailable");
              var off = new OAC(1, frames, target);
              var node = off.createBufferSource();
              node.buffer = audioBuf;
              node.connect(off.destination);
              node.start(0);
              return off.startRendering();
            }).then(function (rendered) {
              var samples = rendered.getChannelData(0);
              var wav = encodeWav(samples, 16000);
              release();
              return { base64: toBase64(wav), seconds: rendered.duration, bytes: wav.length };
            }).catch(function (err) {
              release();
              throw err;
            });
          });
          return stopping;
        },

        /** Drop the current acquisition without producing a payload. */
        cancel: function () {
          if (recorder) {
            try { recorder.stop(); } catch (e) {}
            recorder = null;
          }
          chunks = [];
          release();
        },

        dispose: function () {
          this.cancel();
        },
      };
    }

    // ─────────────────────────────────────────────────────────────────────
    // Session event reading: turn the client Session event window into the
    // answer text the orb displays while it streams.
    // ─────────────────────────────────────────────────────────────────────
    function blocksToText(message) {
      if (!message) return "";
      var content = message.content;
      if (typeof content === "string") return content;
      if (!content || !content.length) return "";
      var out = "";
      for (var i = 0; i < content.length; i++) {
        var block = content[i];
        if (!block) continue;
        if (block.type === "text" && typeof block.text === "string") out += block.text;
      }
      return out;
    }

    /**
     * Collect the answer produced after `afterSeq`.
     * Durable `assistant/message` events win; client-only `assistant/live-chunk`
     * text deltas fill the gap while the model is still streaming.
     */
    function readAnswer(entries, afterSeq) {
      var durable = "";
      var live = {};
      var order = [];
      for (var i = 0; i < entries.length; i++) {
        var entry = entries[i];
        var ev = entry && entry.event;
        if (!ev) continue;
        var seq = typeof ev.seq === "number" ? ev.seq : 0;
        if (entry.type === "event") {
          if (ev.type === "user/message" && seq > afterSeq) {
            durable = ""; live = {}; order = [];
          } else if (ev.type === "assistant/message" && seq > afterSeq) {
            var txt = blocksToText(ev.data && ev.data.message);
            if (txt) durable = txt;
            live = {}; order = [];
          }
        } else if (ev.type === "assistant/live-chunk" && ev.data && ev.data.chunk) {
          var chunk = ev.data.chunk;
          if (chunk.type === "text-delta" && typeof chunk.text === "string") {
            var id = String(ev.data.attemptId || "a");
            if (live[id] === undefined) { live[id] = ""; order.push(id); }
            live[id] += chunk.text;
          }
        }
      }
      var tail = "";
      for (var j = 0; j < order.length; j++) tail += live[order[j]];
      return (durable + tail).trim();
    }

    function lastSeq(entries) {
      var max = 0;
      for (var i = 0; i < entries.length; i++) {
        var ev = entries[i] && entries[i].event;
        if (ev && typeof ev.seq === "number" && ev.seq > max) max = ev.seq;
      }
      return max;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Host bridge — everything here is written defensively: a DSH build
    // without the speech or session plugins still gets a working orb, it just
    // loses the microphone (and falls back to the typed prompt).
    // ─────────────────────────────────────────────────────────────────────
    function createBridge(ctx, log) {
      function service(name) {
        try { return ctx.get(name); } catch (e) { return undefined; }
      }

      // `remote.speech` is mounted by the stock voice-input plugin, so this
      // plugin consumes it through `ctx.inject` rather than reaching for a
      // property on the guarded root context.
      var speechScope = null;

      /** The speech Remote contributed by the stock voice-input plugin. */
      function speech() {
        var holders = [];
        // The gateway installs each Remote namespace as its OWN cordis service
        // under the dotted key (`remote.speech`), so the dotted lookup is the
        // most direct probe; the nested forms are kept as fallbacks.
        holders.push(service("remote.speech"));
        if (speechScope) {
          try { if (speechScope.remote) holders.push(speechScope.remote.speech); } catch (e) {}
          try { holders.push(speechScope.get ? speechScope.get("remote.speech") : undefined); } catch (e) {}
        }
        try { if (ctx.remote) holders.push(ctx.remote.speech); } catch (e) {}
        var viaRemote = service("remote");
        if (viaRemote) { try { holders.push(viaRemote.speech); } catch (e) {} }
        for (var i = 0; i < holders.length; i++) {
          try {
            var ns = holders[i];
            if (ns && typeof ns.transcribe === "function") return ns;
          } catch (e) {}
        }
        return null;
      }

      function sessions() {
        var s = service("sessions");
        return s && typeof s.retain === "function" ? s : null;
      }

      /**
       * The session the conversation view is currently showing.
       * `uiSession` is the view's own authority; the DOM attribute stamped on
       * the conversation root is the fallback.
       */
      function currentSessionId() {
        try {
          var ui = service("uiSession");
          if (ui && ui.adapter && ui.adapter.current) {
            var snap = ui.adapter.current.getSnapshot();
            var id = snap && snap.props && snap.props.sessionId;
            if (id) return id;
          }
        } catch (e) {}
        try {
          var nodes = document.querySelectorAll("[data-conversation-session]");
          for (var i = nodes.length - 1; i >= 0; i--) {
            var domId = nodes[i].getAttribute("data-conversation-session");
            if (domId) return domId;
          }
        } catch (e) {}
        try {
          var m = /(?:session|sessions|s)\/([A-Za-z0-9_-]{8,})/.exec(
            window.location.pathname + window.location.hash + window.location.search);
          if (m) return m[1];
        } catch (e) {}
        return null;
      }

      /**
       * `speech.transcribe` is a generated Remote: it resolves a
       * `RemoteResult<Transcript>` (`{ok:true, value}` / `{ok:false, error}`).
       * Both that and a bare Transcript are accepted here.
       */
      function transcribe(payload, language, signal) {
        var ns = speech();
        if (!ns) return Promise.reject(new Error("speech-unavailable"));
        var request = { audioBase64: payload.base64 };
        if (language) request.language = language;
        return Promise.resolve(ns.transcribe(request, signal)).then(function (res) {
          if (!res) throw new Error("empty-transcription");
          if (res.ok === false) {
            var err = res.error || {};
            throw new Error(String(err.message || err.code || "transcription-failed"));
          }
          var value = res.ok === true ? res.value : res;
          if (!value) throw new Error("empty-transcription");
          return {
            text: String(value.text || "").trim(),
            audioSeconds: value.audioSeconds,
            inferenceSeconds: value.inferenceSeconds,
          };
        });
      }

      /**
       * Send one prompt and stream the answer produced for it.
       * @returns nothing; callbacks carry text, completion and failure.
       */
      function ask(sessionId, text, handlers) {
        var api = sessions();
        if (!api) {
          handlers.onError(new Error("session-unavailable"));
          return null;
        }
        var ref = null;
        var unsubEvents = null;
        var unsubSession = null;
        var poll = 0;
        var baseSeq = 0;
        var sawRunning = false;
        var finished = false;
        var disposed = false;
        var started = Date.now();

        function cleanup() {
          if (poll) { window.clearInterval(poll); poll = 0; }
          if (unsubEvents) { try { unsubEvents(); } catch (e) {} unsubEvents = null; }
          if (unsubSession) { try { unsubSession(); } catch (e) {} unsubSession = null; }
          if (ref) { try { ref.release(); } catch (e) {} ref = null; }
        }

        function finish(reason) {
          if (finished) return;
          finished = true;
          cleanup();
          handlers.onDone(reason);
        }

        try {
          ref = api.retain(sessionId, { source: "controllerOperation" });
        } catch (e) {
          handlers.onError(e);
          return null;
        }

        ref.ready.then(function (binding) {
          if (disposed) return;
          var source = binding.eventSource;
          var session = binding.session;
          baseSeq = lastSeq(source.getSnapshot().entries);

          function tick() {
            if (finished || disposed) return;
            var entries = source.getSnapshot().entries;
            var answer = readAnswer(entries, baseSeq);
            handlers.onText(answer);
            var snap = null;
            try { snap = session.getSnapshot(); } catch (e) {}
            if (snap && snap.running) sawRunning = true;
            if (snap && snap.promptError && !sawRunning) {
              finished = true;
              cleanup();
              handlers.onError(new Error(String(snap.promptError.error && snap.promptError.error.message || snap.promptError.error)));
              return;
            }
            var idle = snap ? !snap.running : true;
            if (sawRunning && idle && answer) finish("done");
            else if (Date.now() - started > 180000) finish("timeout");
          }

          unsubEvents = source.subscribe(tick);
          try { unsubSession = session.subscribe(tick); } catch (e) {}
          poll = window.setInterval(tick, 700);

          session.prompt([{ type: "text", text: text }], "queue").then(function (res) {
            if (disposed) return;
            if (res && res.ok === false) {
              finished = true;
              cleanup();
              handlers.onError(new Error(String(res.error && res.error.message || res.error)));
              return;
            }
            tick();
          }).catch(function (err) {
            if (disposed) return;
            finished = true;
            cleanup();
            handlers.onError(err);
          });
        }).catch(function (err) {
          if (disposed) return;
          handlers.onError(err);
        });

        return {
          cancel: function () {
            disposed = true;
            cleanup();
          },
        };
      }

      /** Last-resort send: drop the text into the stock composer. */
      function fillComposer(text) {
        var input = document.querySelector("[data-composer-input]");
        if (!input) return false;
        try {
          input.focus();
          var sel = window.getSelection();
          if (sel) {
            var range = document.createRange();
            range.selectNodeContents(input);
            range.collapse(false);
            sel.removeAllRanges();
            sel.addRange(range);
          }
          if (!document.execCommand("insertText", false, text)) {
            input.textContent = (input.textContent || "") + text;
            input.dispatchEvent(new InputEvent("input", { bubbles: true }));
          }
          return true;
        } catch (e) {
          return false;
        }
      }

      return {
        setSpeechScope: function (scope) { speechScope = scope; },
        hasSpeech: function () { return speech() !== null; },
        hasSessions: function () { return sessions() !== null; },
        currentSessionId: currentSessionId,
        transcribe: transcribe,
        ask: ask,
        fillComposer: fillComposer,
        log: log,
      };
    }

    // ─────────────────────────────────────────────────────────────────────
    // Speech synthesis (optional). No DSH text-to-speech plugin exists, so the
    // browser's own synthesizer closes the loop and makes the orb talk back.
    // ─────────────────────────────────────────────────────────────────────
    function createVoice(language) {
      var synth = window.speechSynthesis;
      var current = null;
      var levelTimer = 0;

      function pickVoice() {
        if (!synth || !synth.getVoices) return null;
        var want = String(language || "zh").toLowerCase();
        var voices = synth.getVoices() || [];
        var exact = null, prefix = null;
        for (var i = 0; i < voices.length; i++) {
          var v = String(voices[i].lang || "").toLowerCase();
          if (v.indexOf(want) === 0) {
            if (v.indexOf("-") > 0) { exact = voices[i]; break; }
            if (!prefix) prefix = voices[i];
          }
        }
        return exact || prefix || null;
      }

      return {
        available: function () { return !!synth && typeof window.SpeechSynthesisUtterance === "function"; },
        speaking: function () { return !!current; },
        speak: function (text, handlers) {
          if (!synth || typeof window.SpeechSynthesisUtterance !== "function") {
            handlers.onEnd();
            return;
          }
          this.stop();
          var u = new window.SpeechSynthesisUtterance(String(text).slice(0, 4000));
          u.lang = language === "zh" ? "zh-CN" : (language || "zh-CN");
          var v = pickVoice();
          if (v) u.voice = v;
          u.rate = 1.04;
          u.pitch = 1.0;
          u.volume = 1.0;
          current = u;
          var started = Date.now();
          if (handlers.onLevel) {
            levelTimer = window.setInterval(function () {
              var t = (Date.now() - started) / 1000;
              var lv = 0.34 + 0.30 * Math.abs(Math.sin(t * 7.3)) + 0.18 * Math.abs(Math.sin(t * 3.1));
              handlers.onLevel(Math.min(1, lv));
            }, 70);
          }
          function done() {
            if (levelTimer) { window.clearInterval(levelTimer); levelTimer = 0; }
            if (current === u) current = null;
            handlers.onEnd();
          }
          u.onend = done;
          u.onerror = done;
          try { synth.cancel(); synth.speak(u); } catch (e) { done(); }
        },
        stop: function () {
          if (levelTimer) { window.clearInterval(levelTimer); levelTimer = 0; }
          current = null;
          if (synth) { try { synth.cancel(); } catch (e) {} }
        },
        dispose: function () { this.stop(); },
      };
    }

    // ─────────────────────────────────────────────────────────────────────
    // The voice surface: a full-screen takeover that renders the crystal ball
    // and owns the listen → transcribe → ask → speak state machine.
    // ─────────────────────────────────────────────────────────────────────
    var MIC_SVG =
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M12 3.6a3 3 0 0 1 3 3v5.8a3 3 0 0 1-6 0V6.6a3 3 0 0 1 3-3Z"/>' +
      '<path d="M5.6 11.4a6.4 6.4 0 0 0 12.8 0"/><path d="M12 17.8V21"/></svg>';

    function h(tag, props, children) {
      var node = document.createElement(tag);
      if (props) {
        for (var k in props) {
          var v = props[k];
          if (v === undefined || v === null || v === false) continue;
          if (k === "class") node.className = v;
          else if (k === "text") node.textContent = v;
          else if (k === "html") node.innerHTML = v;
          else if (k === "style") node.setAttribute("style", v);
          else node.setAttribute(k, v === true ? "" : String(v));
        }
      }
      if (children) {
        for (var i = 0; i < children.length; i++) {
          var c = children[i];
          if (c) node.appendChild(c);
        }
      }
      return node;
    }

    function createSurface(options) {
      var bridge = options.bridge;
      var params = options.params;
      var log = options.log;

      var els = {};
      var renderer = null;
      var recorder = null;
      var voice = null;
      var state = "idle";
      var open = false;
      var levelTimer = 0;
      var autoStop = 0;
      var pending = null;
      var answerText = "";
      var userText = "";
      var disposed = false;
      var debugLevel = -1;

      // ── DOM ──────────────────────────────────────────────────────────
      var caption = h("div", { class: "dslgs-caption", hidden: true });
      var captionYou = h("span", { class: "dslgs-you" });
      var captionAnswer = h("span", { class: "dslgs-answer" });
      var captionCaret = h("span", { class: "dslgs-caret" });
      caption.appendChild(captionYou);
      caption.appendChild(captionAnswer);
      caption.appendChild(captionCaret);

      var statusText = h("span", { class: "dslgs-status-text", text: "待机" });
      var status = h("div", { class: "dslgs-status" }, [h("span", { class: "dslgs-dot" }), statusText]);
      var toast = h("div", { class: "dslgs-toast", hidden: true });

      var orbCanvas = h("canvas", { class: "dslgs-orb" });
      var fxCanvas = h("canvas", { class: "dslgs-fx" });
      var orbWrap = h("div", { class: "dslgs-orbwrap" }, [
        h("div", { class: "dslgs-halo" }),
        orbCanvas,
        // The light effects composite OVER the ball with screen blending, so
        // they read as light living inside the glass rather than behind it.
        fxCanvas,
        h("div", { class: "dslgs-ring" }),
        h("div", { class: "dslgs-ring d2" }),
      ]);

      var micBtn = h("button", { class: "dslgs-mic", type: "button", title: "开始聆听", html: MIC_SVG });
      var typeBtn = h("button", { class: "dslgs-glass", type: "button", title: "改用键盘输入", text: "⌨" });
      var speakBtn = h("button", {
        class: "dslgs-glass", type: "button", title: "朗读回答",
        "aria-pressed": params.speakReplies ? "true" : "false", text: "🔊",
      });
      var dock = h("div", { class: "dslgs-dock" }, [typeBtn, micBtn, speakBtn]);

      var input = h("input", { type: "text", placeholder: "或直接输入…", "aria-label": "输入内容" });
      var sendBtn = h("button", { class: "dslgs-send", type: "button", text: "发送" });
      var typeBar = h("div", { class: "dslgs-type" }, [input, sendBtn]);

      var closeBtn = h("button", { class: "dslgs-close", type: "button", title: "关闭 (Esc)", text: "✕" });
      var launcher = h("button", {
        class: "dslgs-launcher", type: "button",
        title: "Siri 液态玻璃 (Alt+S)",
        "aria-label": "Siri 液态玻璃语音助手 (Alt+S)",
      });

      var stage = h("div", { class: "dslgs-stage" }, [status, orbWrap, caption, toast]);
      var root = h("div", { class: "dslgs-root", "data-open": "false", "data-state": "idle" }, [
        h("div", { class: "dslgs-scrim" }),
        h("div", { class: "dslgs-edge" }),
        closeBtn,
        stage,
        dock,
        typeBar,
        launcher,
      ]);

      els.root = root; els.caption = caption; els.captionYou = captionYou;
      els.captionAnswer = captionAnswer; els.caret = captionCaret;
      els.statusText = statusText; els.toast = toast; els.input = input;
      els.mic = micBtn; els.speak = speakBtn; els.type = typeBtn;

      // ── plumbing ─────────────────────────────────────────────────────
      try {
        renderer = Renderer({ orb: orbCanvas, fx: fxCanvas }, params, null);
      } catch (e) {
        renderer = null;
        log("webgl unavailable: " + e.message);
      }
      recorder = Recorder();
      voice = createVoice(params.language);

      function setState(next) {
        state = next;
        root.setAttribute("data-state", next);
        var labels = {
          idle: "待机", listening: "聆听中…", transcribing: "识别中…",
          thinking: "思考中…", speaking: "回答中…", error: "出错了",
        };
        statusText.textContent = labels[next] || next;
        if (renderer) {
          renderer.setMode(next === "transcribing" ? "thinking" : next);
          if (next === "listening") renderer.setFx("wave");
          else if (next === "thinking" || next === "transcribing") renderer.setFx("dots");
          else if (next === "speaking") renderer.setFx(params.fxSpeaking || "wave");
        }
        micBtn.title = next === "listening" ? "停止聆听" : "开始聆听";
      }

      function showToast(text) {
        if (!text) { toast.setAttribute("hidden", ""); toast.textContent = ""; return; }
        toast.textContent = text;
        toast.removeAttribute("hidden");
      }

      function setCaption(you, answer, streaming) {
        userText = you || "";
        answerText = answer || "";
        var any = !!(userText || answerText);
        if (any) caption.removeAttribute("hidden"); else caption.setAttribute("hidden", "");
        captionYou.textContent = userText;
        captionYou.style.display = userText ? "" : "none";
        captionAnswer.textContent = answerText;
        captionCaret.style.display = streaming ? "" : "none";
        root.style.setProperty("--dslgs-level", String(Math.min(1, 0.15 + answerText.length / 420)));
      }

      // ── level pump: one clock drives orb glow for every state ────────
      function pump() {
        if (!renderer || !open) return;
        var lv = 0;
        if (state === "listening") lv = recorder ? recorder.level : 0;
        else if (state === "speaking") lv = speakingLevel;
        else if (state === "thinking" || state === "transcribing") lv = 0.28 + 0.10 * Math.sin(Date.now() / 220);
        else lv = 0.06 + 0.04 * Math.sin(Date.now() / 900);
        if (debugLevel >= 0) lv = debugLevel;
        renderer.setLevel(lv);
        root.style.setProperty("--dslgs-edge-a", String(0.10 + 0.55 * lv));
        root.style.setProperty("--dslgs-fx-alpha", String(0.55 + 0.45 * lv));
      }
      var speakingLevel = 0;

      // ── the voice loop ───────────────────────────────────────────────
      function startListening() {
        if (disposed || state === "listening") return;
        if (!bridge.hasSpeech()) {
          showToast("未找到语音识别插件：请确认已启用 dsh-experimental-voice-input-bundle，或直接用键盘输入。");
          root.setAttribute("data-typing", "true");
          input.focus();
          return;
        }
        if (!bridge.currentSessionId()) {
          showToast("请先打开一个会话，再对水晶球说话。");
          return;
        }
        voice.stop();
        setCaption(userText, "", false);
        setState("listening");
        recorder.start().then(function () {
          if (state !== "listening") return;
          autoStop = window.setTimeout(function () {
            if (state === "listening") stopListening();
          }, (params.maxSeconds || 60) * 1000);
        }).catch(function (err) {
          var name = String((err && err.name) || "");
          var msg = String((err && err.message) || err);
          if (name === "NotAllowedError" || msg.indexOf("NotAllowed") >= 0) {
            showToast("麦克风权限被拒绝：请在浏览器地址栏允许本站使用麦克风。");
          } else if (msg.indexOf("microphone-unavailable") >= 0) {
            showToast("当前环境不支持麦克风采集（需要 https 或 localhost）。");
          } else {
            showToast("无法打开麦克风：" + msg);
          }
          setState("idle");
        });
      }

      function stopListening() {
        if (state !== "listening") return;
        if (autoStop) { window.clearTimeout(autoStop); autoStop = 0; }
        setState("transcribing");
        recorder.stop().then(function (payload) {
          if (disposed) return null;
          if (!payload) return null;
          return bridge.transcribe(payload, params.language);
        }).then(function (result) {
          if (disposed) return;
          var text = result && result.text ? result.text : "";
          if (!text) {
            showToast("没有听清，再说一次？");
            setState("idle");
            if (params.continuous) window.setTimeout(function () { if (open) startListening(); }, 700);
            return;
          }
          showToast("");
          setCaption(text, "", false);
          ask(text);
        }).catch(function (err) {
          if (disposed) return;
          showToast("识别失败：" + String((err && err.message) || err));
          setState("error");
          window.setTimeout(function () { if (state === "error") setState("idle"); }, 2200);
        });
      }

      function ask(text) {
        var sessionId = bridge.currentSessionId();
        setState("thinking");
        setCaption(text, "", false);

        if (!sessionId) {
          if (bridge.fillComposer(text)) {
            showToast("已填入输入框——打开一个会话后即可发送。");
          } else {
            showToast("找不到当前会话。");
          }
          setState("idle");
          return;
        }

        pending = bridge.ask(sessionId, text, {
          onText: function (answer) {
            if (disposed) return;
            if (answer) showToast("");
            if (answer && state === "thinking") setState("speaking");
            setCaption(text, answer, true);
          },
          onError: function (err) {
            pending = null;
            if (disposed) return;
            showToast("发送失败：" + String((err && err.message) || err));
            setState("error");
            window.setTimeout(function () { if (state === "error") setState("idle"); }, 2400);
          },
          onDone: function () {
            pending = null;
            if (disposed) return;
            setCaption(text, answerText, false);
            var final = answerText;
            if (params.speakReplies && final && voice.available()) {
              setState("speaking");
              voice.speak(final, {
                onLevel: function (lv) { speakingLevel = lv; },
                onEnd: function () {
                  speakingLevel = 0;
                  if (disposed) return;
                  setState("idle");
                  if (params.continuous && open) {
                    window.setTimeout(function () { if (open && state === "idle") startListening(); }, 620);
                  }
                },
              });
            } else {
              setState("idle");
              if (params.continuous && open) {
                window.setTimeout(function () { if (open && state === "idle") startListening(); }, 620);
              }
            }
          },
        });
      }

      function cancelAll() {
        if (pending) { try { pending.cancel(); } catch (e) {} pending = null; }
        if (autoStop) { window.clearTimeout(autoStop); autoStop = 0; }
        if (recorder && recorder.active) recorder.cancel();
        voice.stop();
        speakingLevel = 0;
      }

      // ── events ───────────────────────────────────────────────────────
      // Prime the audio context on any gesture inside the surface, so the
      // hands-free capture that follows is never blocked as "autoplay".
      root.addEventListener("pointerdown", function () { primeAudio(); }, true);

      micBtn.addEventListener("click", function () {
        primeAudio();
        if (state === "listening") stopListening();
        else if (state === "speaking" || state === "thinking") { cancelAll(); setState("idle"); }
        else startListening();
      });
      typeBtn.addEventListener("click", function () {
        var typing = root.getAttribute("data-typing") === "true";
        root.setAttribute("data-typing", typing ? "false" : "true");
        if (!typing) window.setTimeout(function () { input.focus(); }, 60);
      });
      speakBtn.addEventListener("click", function () {
        params.speakReplies = !params.speakReplies;
        speakBtn.setAttribute("aria-pressed", params.speakReplies ? "true" : "false");
        if (!params.speakReplies) voice.stop();
      });
      closeBtn.addEventListener("click", function () { api.close(); });
      launcher.addEventListener("click", function () { primeAudio(); api.open(); });
      root.addEventListener("click", function (e) {
        if (e.target === root || e.target.classList.contains("dslgs-scrim")) api.close();
      });
      function submitTyped() {
        var text = input.value.trim();
        if (!text) return;
        input.value = "";
        ask(text);
      }
      sendBtn.addEventListener("click", submitTyped);
      input.addEventListener("keydown", function (e) {
        if (e.key === "Enter") { e.preventDefault(); submitTyped(); }
      });
      function onKeyDown(e) {
        if (e.key === "Escape" && open) {
          if (state === "listening" || state === "thinking" || state === "speaking") {
            cancelAll();
            setState("idle");
          } else {
            api.close();
          }
          return;
        }
        var accel = e.altKey || (e.metaKey && e.shiftKey) || (e.ctrlKey && e.shiftKey);
        if (accel && !e.repeat && e.code === params.hotkey) {
          e.preventDefault();
          if (open) api.close(); else api.open();
        }
      }
      window.addEventListener("keydown", onKeyDown, true);

      // ── public surface ───────────────────────────────────────────────
      var api = {
        el: root,
        params: params,
        renderer: renderer,
        get open() { return open; },
        setState: setState,
        setCaption: setCaption,
        /** Pin the orb's audio level, for previews and screenshots (-1 = auto). */
        setDebugLevel: function (v) { debugLevel = typeof v === "number" ? v : -1; },
        getState: function () { return state; },
        open: function () {
          if (open || disposed) return;
          open = true;
          root.setAttribute("data-open", "true");
          showToast(bridge.hasSpeech() ? "" : "未检测到语音插件，可用键盘输入。");
          if (renderer) { renderer.start(); }
          setState("idle");
          if (params.continuous && params.autoListen && bridge.hasSpeech() && bridge.currentSessionId()) {
            window.setTimeout(function () { if (open && state === "idle") startListening(); }, 420);
          } else if (!bridge.hasSpeech()) {
            root.setAttribute("data-typing", "true");
          }
        },
        close: function () {
          if (!open) return;
          cancelAll();
          open = false;
          root.setAttribute("data-open", "false");
          root.setAttribute("data-typing", "false");
          setState("idle");
          setCaption("", "", false);
          showToast("");
          if (renderer) renderer.stop();
        },
        toggle: function () { if (open) api.close(); else api.open(); },
        destroy: function () {
          disposed = true;
          cancelAll();
          window.removeEventListener("keydown", onKeyDown, true);
          if (levelTimer) { window.clearInterval(levelTimer); levelTimer = 0; }
          if (renderer) renderer.stop();
          if (voice) voice.dispose();
          if (recorder) recorder.dispose();
          if (root.parentNode) root.parentNode.removeChild(root);
        },
      };

      levelTimer = window.setInterval(pump, 60);
      pump();
      return api;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Plugin body.
    // ─────────────────────────────────────────────────────────────────────
    function apply(ctx, config) {
      var params = {};
      for (var k in DEFAULTS) params[k] = DEFAULTS[k];
      if (config && typeof config === "object") {
        for (var key in config) {
          if (config[key] !== undefined && config[key] !== null) params[key] = config[key];
        }
      }

      function log(msg) {
        try { if (ctx.logger && ctx.logger.info) ctx.logger.info("[" + PLUGIN_ID + "] " + msg); } catch (e) {}
      }

      try {
        boot(ctx, params, log);
      } catch (error) {
        // A client plugin that throws while mounting leaves no trace a user can
        // see, so surface the failure in the page itself.
        log("mount failed: " + (error && error.stack ? error.stack : error));
        try {
          var banner = document.createElement("div");
          banner.className = "dslgs-boot-error";
          banner.textContent = PLUGIN_ID + " 挂载失败: " + (error && error.message ? error.message : String(error));
          document.body.appendChild(banner);
        } catch (e) {}
      }
    }

    function boot(ctx, params, log) {
      var style = document.createElement("style");
      style.setAttribute("data-plugin", PLUGIN_ID);
      style.textContent = CSS;
      document.head.appendChild(style);

      var bridge = createBridge(ctx, log);
      var surface = createSurface({ bridge: bridge, params: params, log: log });
      document.body.appendChild(surface.el);

      // The speech Remote belongs to the stock voice-input plugin; consuming
      // it here keeps this plugin free of its own transport and provider code.
      ctx.inject(["remote.speech"], function (scoped) {
        bridge.setSpeechScope(scoped);
        log("speech remote available");
        return function () { bridge.setSpeechScope(null); };
      });

      log("ready (speech=" + bridge.hasSpeech() + ", sessions=" + bridge.hasSessions() + ")");

      ctx.effect(function () {
        return function () {
          surface.destroy();
          style.remove();
        };
      }, PLUGIN_ID + ": surface");

      exports.surface = surface;
    }

    exports.apply = apply;

    return module.exports;
  },
});
