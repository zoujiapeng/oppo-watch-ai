# DSH Voice Input — Third-Party Client Plugin Integration Spec

**Scope:** browser-side integration of a custom DSH client plugin with DSH's *existing* speech-to-text stack.
**Installation inspected (read-only):** `/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/`

Path shorthand used below:

| Alias | Expansion |
|---|---|
| `$PKGS` | `/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai` |
| `$VOICE` | `$PKGS/dsh-experimental-client-ui-voice-input` |
| `$SPEECHAPI` | `$PKGS/dsh-experimental-api-speech-to-text` |
| `$STT` | `$PKGS/dsh-experimental-speech-to-text` |
| `$GATEWAY` | `$PKGS/dsh-api-gateway` |
| `$SESSION` | `$PKGS/dsh-api-session-controller` |
| `$CONV` | `$PKGS/dsh-client-ui-conversation` |

Everything in this document was read from the installed artifacts. `lib/client.js` files are generated browser bundles; `lib/types/**/*.d.ts` are the ground truth for signatures; `lib/types/**/*.js` are real ESM sources (non-bundled) for the node/client-service packages.

---

## 0. TL;DR for the integrator

| Question | Answer |
|---|---|
| Is speech-to-text reachable from a third-party client plugin? | **Yes** — via the cordis client service `ctx.remote.speech`, which is a Typert Remote namespace mounted by `@deepseek-ai/dsh-experimental-client-ui-voice-input` (`$VOICE/lib/client.js:5817`). |
| Do I need a WebSocket or HTTP URL / auth token? | **No.** `ctx.remote.*` calls go through the existing client `Connection` carrier (`POST /api/<ns>/<method>` or a worker-local tunnel); auth is the page's existing browser cookie. Streams use `wss://…/api/remote.mux`. A plugin never handles base URL or auth. |
| Can I mount my own `speech` namespace? | **Only if nothing else has.** `remote.$mount` rejects a second contribution that repeats an already-mounted `namespace/method` (`$GATEWAY/lib/client.js:1692`). In the stock profile the voice-input plugin already owns `speech`. |
| Streaming / partial transcripts? | **No.** Transcription is unary over a complete WAV. The only stream in the namespace is `speech/follow`, which carries *readiness* snapshots. |
| Live waveform / level data? | The plugin's own `Recording.amplitude()` — an `AnalyserNode` with `fftSize = 256` + `getFloatTimeDomainData` + RMS, polled from a `requestAnimationFrame` loop throttled to 50 ms (`$VOICE/lib/client.js:4697-4730`, `4844-4869`). |
| Auto-send after transcription? | **No.** The stock plugin only calls `inputActions.insertText(...)` into the composer draft (`$VOICE/lib/client.js:5022`). Programmatic send exists separately (`ctx.conversation.send`). |
| TTS / speaking back? | **None exists.** No text-to-speech / audio-output capability in any of the 293 `@deepseek-ai/*` packages. |

---

## (a) The stock flow, step by step

### a.1 Where the button lives

`$VOICE/lib/client.js:5793-5797` — the mic is a **`conversation.input.activity`** slot occupant (slot declared at `$CONV/lib/types/client/contract/slots.d.ts:240-244`):

```js
ctx.slots.inject("conversation.input.activity", () => ctx.slots.register({
    name: "conversation.input.activity",
    locale: NS,
    inject: () => actions
}, VoiceInput));
```

`VoiceInput` receives `sessionId`, `inputActions`, `locked`, `onActiveChange` + injected host actions (`$VOICE/lib/types/client/VoiceInput.d.ts:26-28`):

```ts
export type VoiceInputProps = Pick<PropsRuntime<'conversation.input.activity'>, 'sessionId' | 'inputActions' | 'locked' | 'onActiveChange'> & PropsLocale<typeof NS> & InjectFace<VoiceInputInjected>;
```

### a.2 Click → readiness gate

`$VOICE/lib/client.js:5089-5092` (trigger button):

```js
onClick: () => {
    if (usable) start();
    else setSetupOpen(true);
},
```

with (`$VOICE/lib/client.js:4926-4928`):

```js
const readiness = useSpeechReadiness((value) => value), catalog = readiness.catalog;
const provider = catalog?.providers.find((item) => item.id === catalog.selection.providerId);
const usable = readiness.connected && (provider?.preparation.phase === "ready" || provider?.preparation.phase === "standby" || provider?.preparation.phase === "waking");
```

### a.3 `start()` — getUserMedia

`$VOICE/lib/client.js:5035-5072`:

```js
const start = async () => {
    if (!catalog || !usable || locked || current.current) return;
    const run = ++generation.current;
    const active = {
        capture: createRecording(),
        abort: new AbortController(),
        span: inputActions.captureInsertion(),
        selection: catalog.selection,
        maxDurationSeconds: catalog.maxDurationSeconds,
        maxAudioBytes: catalog.maxAudioBytes,
        phase: "requesting"
    };
    current.current = active;
    ...
    await active.capture.start((failure) => { ... });
    ...
    active.phase = "recording";
    setPhase("recording");
    active.timer = setTimeout(() => { finish(); }, active.maxDurationSeconds * 1e3);
```

`createRecording()` is the injected action that returns one `Recording` (`$VOICE/lib/client.js:5772-5778`):

```js
createRecording: () => {
    const recording = new Recording(() => { recordings.delete(recording); });
    recordings.add(recording);
    return recording;
},
```

### a.4 `Recording.start()` — the actual browser capture

`$VOICE/lib/client.js:4673-4719` (full quote):

```js
async start(onError) {
    const devices = navigator.mediaDevices;
    if (!devices || typeof MediaRecorder === "undefined") throw new RecordingError("unavailable");
    let stream;
    try {
        stream = await devices.getUserMedia({
            audio: {
                echoCancellation: true,
                noiseSuppression: true
            },
            video: false
        });
    } catch (error) {
        if (error instanceof DOMException && error.name === "NotAllowedError") throw new RecordingError("permission");
        throw error;
    }
    if (this.lifetime.signal.aborted) {
        stream.getTracks().forEach((track) => { track.stop(); });
        throw new RecordingError("cancelled");
    }
    this.stream = stream;
    try {
        this.context = new AudioContext();
        this.analyser = this.context.createAnalyser();
        this.analyser.fftSize = this.samples.length;      // samples = new Float32Array(256)
        this.context.createMediaStreamSource(stream).connect(this.analyser);
        this.recorder = new MediaRecorder(stream);        // NOTE: no mimeType option
        this.recorder.ondataavailable = (event) => {
            if (!this.lifetime.signal.aborted && event.data.size > 0) this.chunks.push(event.data);
        };
        this.recorder.onerror = () => { ... onError?.(new RecordingError("interrupted")); };
        this.recorder.start();
    } catch (error) {
        await this.dispose();
        throw error;
    }
}
```

> **No `mimeType` is requested**, so the container/codec is the browser default (Chromium: `audio/webm;codecs=opus`). The plugin never inspects it except to pass `recorder.mimeType` into the `Blob` at `stop()`.

### a.5 `finish()` — stop → WAV → transcribe → **draft**

`$VOICE/lib/client.js:4995-5034` (the core of the flow):

```js
const finish = async () => {
    const active = current.current;
    if (!active || active.phase !== "recording") return;
    active.phase = "transcribing";
    const run = generation.current;
    clearTimeout(active.timer);
    setPhase("transcribing");
    try {
        const audio = await active.capture.stop(active.maxDurationSeconds);
        if (run !== generation.current) return;
        if (audio.byteLength > active.maxAudioBytes) {
            feedback(t("tooLarge"));
            return;
        }
        const result = await transcribe({
            audioBase64: audioBase64(audio),
            ...active.selection
        }, active.abort.signal);
        if (run !== generation.current) return;
        if (!result.ok) {
            feedback(t("failed", { message: result.error.message }));
            return;
        }
        if (result.value.text === "") {
            feedback(t("empty"));
            return;
        }
        if (!inputActions.insertText(result.value.text, active.span)) {
            setPending(result.value.text);
            feedback(t("conflict"));
            return;
        }
        setPhase("idle");
    } catch (failure) { ... }
    finally { if (run === generation.current) current.current = void 0; }
};
```

**The transcript goes into the composer DRAFT, never auto-sent.** `inputActions.insertText(text, span)` is a revision-guarded draft insertion (`$CONV/lib/types/client/contract/input.d.ts:215`); on conflict the text is retained in `pending` for an explicit "Insert" button (`$VOICE/lib/client.js:5139-5149`).

The package README states this explicitly (`$VOICE/README.md`, "Known Limitations"):
> `- No automatic send, always-on microphone, wake word, streaming captions or speech synthesis.`

### a.6 `stop()` — resample to 16 kHz mono PCM16 WAV

`$VOICE/lib/client.js:4736-4772`:

```js
async stop(maxDurationSeconds) {
    const recorder = this.recorder;
    const context = this.context;
    if (!recorder || !context || recorder.state !== "recording") {
        await this.dispose();
        throw new RecordingError("empty");
    }
    try {
        await new Promise((resolve, reject) => {
            recorder.onstop = () => { resolve(); };
            recorder.onerror = () => { reject(new RecordingError("empty")); };
            recorder.stop();
        });
        this.stream?.getTracks().forEach((track) => { track.stop(); });
        this.lifetime.signal.throwIfAborted();
        const blob = new Blob(this.chunks, { type: recorder.mimeType });
        if (blob.size === 0) throw new RecordingError("empty");
        const decoded = await context.decodeAudioData(await blob.arrayBuffer());
        this.lifetime.signal.throwIfAborted();
        const offline = new OfflineAudioContext(1, Math.max(1, Math.floor(Math.min(decoded.duration, maxDurationSeconds) * 16e3)), 16e3);
        const source = offline.createBufferSource();
        source.buffer = decoded;
        source.connect(offline.destination);
        source.start();
        const resampled = await offline.startRendering();
        this.lifetime.signal.throwIfAborted();
        return encodeWave(resampled.getChannelData(0));
    } finally {
        await this.dispose();
    }
}
```

`encodeWave` writes a canonical **16 kHz, mono, 16-bit PCM** RIFF/WAVE header (`$VOICE/lib/client.js:4619-4643`); `audioBase64` base64-encodes it in 8 KiB slices (`$VOICE/lib/client.js:4649-4653`):

```js
function encodeWave(samples) {
    const bytes = new Uint8Array(44 + samples.length * 2);
    const view = new DataView(bytes.buffer);
    ...
    view.setUint16(22, 1, true);      // channels = 1
    view.setUint32(24, 16e3, true);   // sampleRate = 16000
    view.setUint32(28, 32e3, true);   // byteRate
    view.setUint16(32, 2, true);      // blockAlign
    view.setUint16(34, 16, true);     // bitsPerSample = 16
    ...
}
function audioBase64(bytes) {
    let text = "";
    for (let i = 0; i < bytes.length; i += 8192) text += String.fromCharCode(...bytes.subarray(i, i + 8192));
    return btoa(text);
}
```

### a.7 Full ordered call chain

```
[mount]   ctx.remote.$mount(TYPERT_REMOTE)            // $VOICE/lib/client.js:5818
          ctx.inject(["remote.speech","slots","locale","pluginNavigation"], registerUi)  // :5819
[ready]   ctx.remote.speech.follow(signal)  ──►  readiness snapshots (SpeechCatalog)   // :5400
[click]   inputActions.captureInsertion()   → TokenSpan                                // :5041
          new Recording()                                                             // :5039 / :5772
[start]   navigator.mediaDevices.getUserMedia({audio:{echoCancellation,noiseSuppression},video:false})  // :4678
          new AudioContext() + createAnalyser(fftSize=256) + createMediaStreamSource  // :4697-4700
          new MediaRecorder(stream); recorder.start()                                  // :4701 / :4714
[record]  Waveform rAF loop → recording.amplitude() at ≤20 Hz                          // :4844-4869
[stop]    recorder.stop() → Blob → AudioContext.decodeAudioData → OfflineAudioContext(1, n, 16000)
          → encodeWave() → Uint8Array (16 kHz mono PCM16 WAV)                          // :4744-4768
[call]    ctx.remote.speech.transcribe({audioBase64, providerId, language}, signal)    // :5779 / :5009
          └─ Connection RPC  POST /api/speech/transcribe  {args:{request:{…}}}         // $GATEWAY/lib/client.js:1792
             └─ Host SpeechController.transcribe → ctx.speechToText.transcribe        // $SPEECHAPI/lib/index.js:191-210
                └─ sensevoice worker → {text, audioSeconds, inferenceSeconds}
[result]  inputActions.insertText(text, span)   // DRAFT ONLY — no send               // :5022
```

---

## (b) Copy-pasteable integration recipe for a custom client plugin

### b.0 Package shape (this is the whole third-party contract)

A DSH client plugin is a **node-half package + a browser bundle**, wired by `package.json`:

```jsonc
// package.json  — modelled on the installed third-party plugin
// ~/.dsh/profiles/web/node_modules/dsh-web-mobile-fix/package.json
{
  "name": "my-voice-plugin",
  "type": "module",
  "main": "lib/index.js",
  "exports": {
    ".": "./lib/index.js",
    "./client": "./lib/client.js",     // <-- the browser bundle
    "./package.json": "./package.json"
  },
  "dsh": {
    "bundle":  { "patch": "./cordis.patch.yml" },
    "client":  {
      "platform": "web",
      // module-table deps: only needed if you require() another plugin's bundle
      "external": ["@deepseek-ai/dsh-api-gateway/client"],
      // package rows whose client halves must exist; also cordis composition edges
      "inject": ["@deepseek-ai/dsh-experimental-client-ui-voice-input"]
    }
  }
}
```

```js
// lib/index.js  — host half; empty apply is enough (see dsh-web-mobile-fix/lib/index.js)
function apply() {}
export { apply };
```

```yaml
# cordis.patch.yml — mounts the row (see dsh-web-mobile-fix/cordis.patch.yml)
- insert:
    - id: my-voice
      name: 'my-voice-plugin'
```

The bundle is a **lazy CJS factory registration**, not an ESM module (`$PKGS/dsh-client-modules/lib/types/client/manifest.d.ts:9-16, 176-188`):

```js
// lib/client.js
window.__ModuleLoader__.load({
  id: "my-voice-plugin",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    /* … bundle body … */
    exports.apply = apply;
    exports.inject = ["remote", "slots", "locale"];
    return module.exports;
  }
});
```

**Static module table at boot** (verified in `$PKGS/dsh-web-frontend/dist/assets/index-*.js`, `function rM(){return{…}}`):
`react`, `react/jsx-runtime`, `react-dom`, `react-dom/client`, `@deepseek-ai/cordis`, `@deepseek-ai/dsh-client-store`, `@deepseek-ai/dsh-client-ui-slots`, `@deepseek-ai/dsh-client-ui-primitives`, `@deepseek-ai/dsh-client-ui-dockkit`. Anything else must be requested through `dsh.client.external` and resolved to another plugin's graph row (`stripClientSuffix` maps `<pkg>/client` → `<pkg>`, `manifest.d.ts:151-160`).

> ⚠️ `@deepseek-ai/dsh-experimental-api-speech-to-text/remote` is **not resolvable at runtime**. That package has no `dsh.client` declaration (`$SPEECHAPI/package.json` has no `dsh` key), so it has no module-table row. The stock plugin does not import it either — it **inlines** it: `$VOICE/lib/client.js:4080` carries the region marker `//#region ../api-speech-to-text/lib/typert.remote-client.js`, and the whole descriptor table + bundled zod live in that region (lines 4080-4601).

### b.1 The complete recipe

```js
window.__ModuleLoader__.load({
  id: "my-voice-plugin",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    // ── 1. Capture (distilled from $VOICE/lib/client.js:4606-4802) ─────────────
    var SAMPLE_COUNT = 256;

    function encodeWave(samples) {                 // $VOICE/lib/client.js:4619
      var bytes = new Uint8Array(44 + samples.length * 2);
      var view = new DataView(bytes.buffer);
      var text = function (at, value) { for (var i = 0; i < value.length; i++) bytes[at + i] = value.charCodeAt(i); };
      text(0, "RIFF"); view.setUint32(4, bytes.length - 8, true); text(8, "WAVE");
      text(12, "fmt "); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
      view.setUint16(22, 1, true); view.setUint32(24, 16e3, true); view.setUint32(28, 32e3, true);
      view.setUint16(32, 2, true); view.setUint16(34, 16, true);
      text(36, "data"); view.setUint32(40, samples.length * 2, true);
      for (var i = 0; i < samples.length; i++) {
        var v = Math.max(-1, Math.min(1, samples[i]));
        view.setInt16(44 + i * 2, Math.round(v * (v < 0 ? 32768 : 32767)), true);
      }
      return bytes;
    }

    function audioBase64(bytes) {                  // $VOICE/lib/client.js:4649
      var text = "";
      for (var i = 0; i < bytes.length; i += 8192) text += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
      return btoa(text);
    }

    function Recording() {
      this.stream = undefined; this.recorder = undefined; this.context = undefined;
      this.analyser = undefined; this.samples = new Float32Array(SAMPLE_COUNT);
      this.chunks = []; this.lifetime = new AbortController(); this.disposal = undefined;
      this.levels = new Float32Array(80);
    }
    Recording.prototype.start = async function () {                 // $VOICE/lib/client.js:4673
      var devices = navigator.mediaDevices;
      if (!devices || typeof MediaRecorder === "undefined") throw new Error("unavailable");
      var stream;
      try {
        stream = await devices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false });
      } catch (error) {
        if (error instanceof DOMException && error.name === "NotAllowedError") throw new Error("permission");
        throw error;
      }
      if (this.lifetime.signal.aborted) { stream.getTracks().forEach(function (t) { t.stop(); }); throw new Error("cancelled"); }
      var self = this;
      this.stream = stream;
      this.context = new AudioContext();
      this.analyser = this.context.createAnalyser();
      this.analyser.fftSize = this.samples.length;                  // 256
      this.context.createMediaStreamSource(stream).connect(this.analyser);
      this.recorder = new MediaRecorder(stream);                    // browser default mimeType
      this.recorder.ondataavailable = function (event) {
        if (!self.lifetime.signal.aborted && event.data.size > 0) self.chunks.push(event.data);
      };
      this.recorder.start();
    };

    // ── 2. Live level (exactly the stock mechanism) ────────────────────────────
    Recording.prototype.amplitude = function () {                   // $VOICE/lib/client.js:4724
      if (!this.analyser) return 0;
      this.analyser.getFloatTimeDomainData(this.samples);
      var sum = 0;
      for (var i = 0; i < this.samples.length; i++) sum += this.samples[i] * this.samples[i];
      return Math.sqrt(sum / this.samples.length);                  // RMS in [0, ~1]
    };

    Recording.prototype.stop = async function (maxDurationSeconds) {  // $VOICE/lib/client.js:4736
      var recorder = this.recorder, context = this.context;
      if (!recorder || !context || recorder.state !== "recording") throw new Error("empty");
      await new Promise(function (resolve, reject) {
        recorder.onstop = function () { resolve(); };
        recorder.onerror = function () { reject(new Error("empty")); };
        recorder.stop();
      });
      this.stream.getTracks().forEach(function (t) { t.stop(); });
      var blob = new Blob(this.chunks, { type: recorder.mimeType });
      if (blob.size === 0) throw new Error("empty");
      var decoded = await context.decodeAudioData(await blob.arrayBuffer());
      var offline = new OfflineAudioContext(1, Math.max(1, Math.floor(Math.min(decoded.duration, maxDurationSeconds) * 16e3)), 16e3);
      var source = offline.createBufferSource();
      source.buffer = decoded; source.connect(offline.destination); source.start();
      var resampled = await offline.startRendering();
      await this.dispose();
      return encodeWave(resampled.getChannelData(0));
    };

    Recording.prototype.dispose = function () {                      // $VOICE/lib/client.js:4777
      var self = this;
      if (!this.disposal) {
        this.disposal = (async function () {
          self.lifetime.abort(new Error("cancelled"));
          if (self.recorder && self.recorder.state === "recording") self.recorder.stop();
          if (self.stream) self.stream.getTracks().forEach(function (t) { t.stop(); });
          self.stream = undefined;
          var ctx = self.context; self.context = undefined; self.analyser = undefined; self.chunks = [];
          if (ctx && ctx.state !== "closed") await ctx.close();
        })();
      }
      return this.disposal;
    };

    // ── 3. Plugin body ────────────────────────────────────────────────────────
    var inject = ["remote", "slots", "locale"];   // cordis service names this plugin reads

    function apply(ctx) {
      // 3a. wait for the speech namespace the stock voice-input plugin mounts
      ctx.inject(["remote.speech"], function (sctx) {
        var speech = sctx.remote.speech;          // ClientRemote namespace service

        // 3b. readiness: SpeechCatalog { providers[], selection{providerId,language},
        //                               maxAudioBytes, maxDurationSeconds }
        var catalog = null;
        var stream = sctx.remote.$stream({
          name: "my-voice readiness",
          open: function (signal) { return speech.follow(signal); },
          ended: function () { return new Error("readiness stream ended"); }
        });
        (async function () {
          for await (var item of stream) { catalog = item.value; item.accept(); }
        })();

        async function dictate(sessionId) {
          if (!catalog) throw new Error("speech not ready");
          var recording = new Recording();
          try {
            await recording.start();                                     // may throw "permission"
            // 3c. live level loop for your own visualiser
            var raf = requestAnimationFrame(function tick(now) {
              // recording.amplitude() → 0..~1 ; the stock Waveform draws at ≤20 Hz
              raf = requestAnimationFrame(tick);
            });
            // … your Stop button resolves here …
            var wav = await recording.stop(catalog.maxDurationSeconds);
            if (wav.byteLength > catalog.maxAudioBytes) throw new Error("too large");

            // 3d. TRANSCRIBE — the single call that matters
            var result = await speech.transcribe({
              audioBase64: audioBase64(wav),
              providerId: catalog.selection.providerId,   // e.g. "sensevoice-local"
              language:   catalog.selection.language      // e.g. "zh"
            }, new AbortController().signal);
            // result : RemoteResult<{ text, audioSeconds, inferenceSeconds }>
            if (!result.ok) throw new Error(result.error.code + ": " + result.error.message);
            return result.value.text;
          } finally {
            await recording.dispose();
          }
        }

        // 3e. SEND — three options, pick one (see §5)
        async function sendToActiveSession(sessionId, text) {
          if (text === "") return;

          // (i) highest level: programmatic queued turn, bypasses the composer
          var scope = sctx.get("sessions") && sctx.get("sessions").scope(sessionId);
          if (scope) { await scope.conversation.send(text); return; }

          // (ii) explicit-session form, no scope juggling
          //     ctx.conversation.sendSession(session, text, [], "queue", signal)

          // (iii) lowest level: the raw prompt RPC
          //     await sctx.remote.session.prompt({
          //       requestId: crypto.randomUUID(), sessionId: sessionId,
          //       mode: "queue", content: [{ type: "text", text: text }],
          //       clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone
          //     });
        }

        // 3f. optional: write into the composer draft instead (stock behaviour)
        function insertIntoDraft(sessionId, text) {
          var scope = sctx.get("sessions").scope(sessionId);
          if (scope) sctx.conversation.input.for(scope).setDraft(text);
        }

        ctx.effect(function () { return function () { stream.dispose(); }; });
      });
    }

    exports.apply = apply;
    exports.inject = inject;
    return module.exports;
  }
});
```

### b.2 Getting the active session id without a slot

Two supported routes:

1. **Be a slot occupant.** Register on `conversation.input.activity` (or any `session`-scoped slot) and take `sessionId` from props. The active session's `sessionId` is a merged member of `SessionStandardProps` (`$PKGS/dsh-client-ui-session/lib/types/client/index.d.ts:77-86`):
   ```ts
   interface SessionStandardProps {
       useSession: SessionSnapshotSelector;
       sessionId: SessionId;
       useProjection: UseProjection;
   }
   ```
2. **Read the scope adapter.** `ctx.uiSession.adapter.current` is a `HostObservable<StandardSourceBinding>`; `getSnapshot().key` / `.props.sessionId` is the currently selected session (`$PKGS/dsh-client-ui-session/lib/client.js:167-169, 282-289`; types at `$PKGS/dsh-client-ui-slots/lib/types/renderer.d.ts:64-88`).

---

## (c) Exact service / method reference

### c.1 Cordis client services

| Service key | Type | Provided by | How to obtain |
|---|---|---|---|
| `remote` | `ClientRemote extends TypertClientRemote` | `$GATEWAY/lib/client.js:1595` (`super(ctx, "remote")`), installed by `$PKGS/dsh-api-remotes` (`dsh.client.immediately: true`) | `ctx.remote` / `ctx.get("remote")` |
| `remote.speech` | `TypertRemoteNamespace$737065656368` | mounted by `$VOICE/lib/client.js:5818` via `ctx.remote.$mount(TYPERT_REMOTE)`; the service key is `remote.${namespace}` (`$GATEWAY/lib/client.js:2040-2042`) | `ctx.inject(["remote.speech"], cb)` → `ctx.remote.speech` |
| `remote.session` | session namespace | `$PKGS/dsh-api-session-controller` client half | `ctx.remote.session.*` |
| `sessions` | `ISessions` | `$SESSION/lib/types/client/index.d.ts:26-31` | `ctx.sessions` |
| `conversation` | `IConversation` / `ConversationController` | `$CONV/lib/client.js:3362` (`super(ctx, "conversation")`) | `ctx.conversation` (scope-addressed) |
| `uiSession` | `UiSession` | `$PKGS/dsh-client-ui-session/lib/client.js` | `ctx.uiSession` |
| `uiConversation` | conversation UI shell | `$CONV/lib/client.js:3043` | `ctx.uiConversation` |
| `connection` | `ConnectionHandle` | `$PKGS/dsh-client-connection` | `ctx.connection` |
| `speechToText` **(host only)** | `SpeechToText` registry | `$STT/lib/types/index.d.ts:6-11` | node half only |
| `speechController` **(host only)** | `SpeechController extends TypertRemoteService` | `$SPEECHAPI/lib/index.js:141` `super(ctx, "speechController", { namespace: "speech" })` | node half only |

### c.2 `remote.speech` — full generated surface

`$SPEECHAPI/lib/typert.remote-client.d.ts:11-29` (verbatim):

```ts
interface TypertRemoteNamespace$737065656368 {
  cancelPreparation: (providerId: SpeechProviderId) => Promise<RemoteResult<void>>
  catalog: () => Promise<RemoteResult<SpeechCatalog>>
  configure: (patch: SpeechSelectionPatch) => Promise<RemoteResult<void>>
  follow: (signal?: AbortSignal) => RemoteStreamHandle<SpeechCatalog, never>
  prepare: (providerId: SpeechProviderId, options?: SpeechPreparationOptions) => Promise<RemoteResult<void>>
  transcribe: (request: TranscriptionRequest, signal?: AbortSignal) => Promise<RemoteResult<Transcript>>
}
```

Wire endpoints (from `$SPEECHAPI/lib/typert.remote-client.js`, `TYPERT_REMOTE.descriptors`):

| Endpoint id | mode | args | result |
|---|---|---|---|
| `speech/catalog` | unary | `{}` | `SpeechCatalog` |
| `speech/follow` | **stream** | `{}` (+ caller signal) | `SpeechCatalog` items |
| `speech/configure` | unary | `{patch}` | `void` |
| `speech/prepare` | unary | `{providerId, options?}` (`options` accepts undefined) | `void` |
| `speech/cancelPreparation` | unary | `{providerId}` | `void` |
| `speech/transcribe` | unary | `{request}` + cancellation `signal` | `Transcript` |

### c.3 Payload shapes

`$SPEECHAPI/lib/types/types.d.ts`:

```ts
export interface TranscriptionRequest {
    readonly audioBase64: string;
    readonly providerId?: SpeechProviderId;
    readonly language?: string;
}
export interface SpeechCatalog extends SpeechSnapshot {
    readonly maxAudioBytes: number;
    readonly maxDurationSeconds: number;
}
```

`$STT/lib/types/types.d.ts`:

```ts
export interface Transcript {
    readonly text: string;              // "" means no speech recognized
    readonly audioSeconds: number;
    readonly inferenceSeconds: number;
}
export interface SpeechSnapshot {
    readonly providers: readonly SpeechProviderView[];
    readonly selection: SpeechSelection;          // { providerId, language }
}
export interface SpeechProviderView extends SpeechProviderInfo {
    readonly preparation: SpeechPreparationState;
}
export type SpeechPreparationState = ({ readonly phase: 'unprepared' | 'ready' | 'standby' | 'cancelled'; }
  | { readonly phase: 'downloading'; readonly resource: string; readonly completedBytes: number; readonly totalBytes?: number; }
  | { readonly phase: 'checking' | 'loading' | 'waking' | 'cancelling'; readonly startedAt: number; }
  | { readonly phase: 'failed'; readonly message: string; readonly download?: SpeechDownloadFailure; })
  & { readonly step?: SpeechPreparationStepKind; readonly steps?: readonly SpeechPreparationStep[]; };
export interface SpeechProviderInfo {
    readonly id: SpeechProviderId;
    readonly name: string;
    readonly location: 'host-local' | 'cloud';
    readonly languages: readonly string[];
    readonly setupEstimate?: SpeechSetupEstimate;
    readonly downloadSources?: readonly string[];
}
```

Host-side intake limits (`$SPEECHAPI/lib/index.js:136-139`):

```js
static Config = z.object({
    maxAudioBytes: z.natural().min(46).default(4 * 1024 * 1024),   // 4 MiB decoded WAV
    maxDurationSeconds: z.number().min(1).default(120)             // 120 s
});
```

Wire codecs (client never invokes them, only checks `mode === 'strict'`):
`$SPEECHAPI/lib/typert.remote-client.js` — `audioBase64: z.string().readonly()`, `providerId`/`language` optional strings, result `{text: string, audioSeconds: number, inferenceSeconds: number}`.

### c.4 Result envelope

`RemoteResult<T>` (`$PKGS/dsh-typert-protocol/lib/types/types.d.ts:67-73`):

```ts
export type RemoteResult<T> = { readonly ok: true; readonly value: T }
                            | { readonly ok: false; readonly error: RemoteFailure };
```

`RemoteFailure` is code-discriminated; the speech namespace merges two codes (`$SPEECHAPI/lib/types/types.d.ts:4-13`):

```ts
interface RemoteErrorDetailsMap {
    'speech/invalid-audio': { readonly reason: string };
    'speech/transcription-failed': { readonly reason: string };
}
```

Gateway infrastructure codes: `gateway/bad-request`, `gateway/cancelled`, `gateway/internal` (`$PKGS/dsh-typert-protocol/lib/types/types.d.ts:41-50`). Discrimination helper: `isRemoteFailure` (`$GATEWAY/lib/types/client/index.d.ts:78`).

### c.5 Message submission surface

| Call | Signature | Notes |
|---|---|---|
| `ctx.conversation.send` | `send(text: string): Promise<void>` — `$CONV/lib/types/client/service.d.ts:39` | queued turn, one text block; **requires an agent-scoped ctx** |
| `ctx.conversation.sendSession` | `sendSession(session: SessionFace, text: string, attachmentIds: readonly DraftAttachmentId[], mode: InputSubmitMode, signal?: AbortSignal): Promise<SubmitOutcome>` — `$CONV/lib/types/client/service.d.ts:112` | explicit session, no scope needed |
| `ctx.conversation.input.for` | `for(actx: Context): SessionInput` — `$CONV/lib/types/client/contract/input.d.ts:196-199` | draft-only / submit-only access |
| `SessionInput.setDraft` | `setDraft(text: string): void` — `…/input.d.ts:165` | replace whole draft |
| `SessionInput.submit` | `submit(mode?: InputSubmitMode, source?: 'click' \| 'enter'): void` — `…/input.d.ts:176` | enters the composer submission machine |
| `ISession.prompt` | `prompt(content: PromptContentPart[], mode: 'queue' \| 'steer', signal?: AbortSignal, requestId?: SessionRequestId): Promise<RemoteResult<{accepted: true}>>` — `$SESSION/lib/types/client/contract/session.d.ts:82-84` | object-layer send |
| `ISession.beginSubmission` | `beginSubmission(input: BeginSubmissionInput): SubmissionHandle` — `…/session.d.ts:73` | local echo **before** the RPC |
| `remote.session.prompt` | `(request: SessionPromptRequest, signal?) => Promise<RemoteResult<SessionPromptValue>>` — `$SESSION/lib/typert.remote-client.d.ts:29` | lowest level |
| `ctx.sessions.scope` | `scope(id: SessionId): AgentContext \| undefined` — `$SESSION/lib/types/client/contract/sessions.d.ts:135` | the bridge to `scope.conversation` |

`SessionPromptRequest` (`$SESSION/lib/types/types.d.ts:319-331`):

```ts
export interface SessionPromptRequest {
    readonly requestId: SessionRequestId;                     // any non-empty string at runtime
    readonly sessionId: SessionId;
    readonly mode: 'queue' | 'steer';
    readonly content: readonly PromptContentPart[];           // ≥1 non-whitespace text part or attachment
    readonly clientTimeZone?: string;
}
export interface SessionPromptValue { readonly accepted: true; }
export type PromptContentPart =
    | { readonly type: 'text'; readonly text: string }
    | { readonly type: 'image'; readonly mediaType: ImageMediaType; readonly data: string; readonly name?: string }
    | { readonly type: 'file'; readonly receiptId: Branded<'file-upload-receipt-id'> };
```

Wire schema confirms `requestId` is only `z.string()` (`$SESSION/lib/typert.remote-client.js:784-801`) — no UUID validation client-side.

### c.6 Stock composer's own send path (for reference)

`$CONV/lib/client.js:3378-3389`:

```js
async send(text) {
    const result = await this.scopedSession("send").prompt([{
        type: "text",
        text
    }], "queue");
    if (!result.ok) throw new Error(`conversation.send failed: ${result.error.code}: ${result.error.message}`);
}
```

and the scoped resolver (`$CONV/lib/client.js:3675-3686`):

```js
scopedSession(op) {
    const id = this.scopeId(op);
    const binding = this.requireSessions().binding(id);
    if (binding === void 0) throw new Error(`conversation.${op}: session "${id}" resolved no binding`);
    return binding.session;
}
scopeId(op) {
    const id = this.requireSessions().scopeOf(this.ctx);
    if (id === void 0) throw new Error(`conversation.${op} requires a session scope — address one via ctx.sessions.scope(id).conversation`);
    return id;
}
```

The full submit-with-echo path (`$CONV/lib/client.js:3437-3464`):

```js
const submission = session.beginSubmission({
    mode, text, attachments: pendingAttachments,
    onRetire: (settlement) => { ... }
});
...
if (!(await session.prompt(content, mode, signal, submission.requestId)).ok) return { kind: "error" };
```

### c.7 Transport details (so you know you don't need them)

Unary call (`$GATEWAY/lib/client.js:1792`):

```js
const result = await connection.rpc.call("/api", endpoint, { args: prepared.args }, prepared.signal);
```

which becomes (`$PKGS/dsh-client-connection/lib/client.js:1210-1231`):

```js
const send = doFetch ?? ((input, init) => globalThis.fetch(input, init));
...
const message = { type: "client-request", rpcId, method: endpoint, payload };
const response = await send(`${channel}/${endpoint}`.slice(1), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(message),
    ...signal === void 0 ? {} : { signal }
});
```

→ `POST /api/speech/transcribe` with body `{"type":"client-request","rpcId":"…","method":"speech/transcribe","payload":{"args":{"request":{"audioBase64":"…","providerId":"…","language":"…"}}}}`, cookie-authenticated same-origin (`$PKGS/dsh-client-connection/lib/types/browser-auth.d.ts`).

Streams (`$GATEWAY/lib/client.js:730-735`):

```js
function remoteStreamUrl() {
    const globals = globalThis;
    const url = new URL(REMOTE_STREAM_MUX_PATH.slice(1), globals.__DSH_TRANSPORT__?.streamBaseUrl ?? document.baseURI);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    return url.href;
}
```

`REMOTE_STREAM_MUX_PATH = "/api/remote.mux"` (`$GATEWAY/lib/types/stream-protocol.d.ts:4`). The host resolves the endpoint `namespace/method` by splitting on `/` and requiring exactly two non-empty segments (`$GATEWAY/lib/index.js:1225-1228`), against the Typert registry (`$GATEWAY/lib/index.js:697-704`). **The namespace must literally be `speech`.**

---

## (d) TTS / speech output findings

**There is no text-to-speech, speech-synthesis, or audio-output capability installed.**

Evidence (search over all 293 package directories in `$PKGS`):

```bash
$ ls -d $PKGS/*/ | wc -l
293
$ ls -d $PKGS/*/ | grep -iE "tts|speak|synth|audio|voice|sound|say"
dsh-experimental-client-ui-voice-input/
dsh-experimental-speech-to-text-sensevoice/
dsh-experimental-voice-input-bundle/

$ grep -rliE "speechsynthesis|texttospeech|text-to-speech|tts\b|speak\(" --include=*.js --include=*.d.ts $PKGS
./dsh-client-ui-settings-account/lib/client.js          # false positive: base64 blob contains "TTS"
./dsh-web-frontend/dist/assets/langs/less-B1dDrJ26.js   # false positive: "less" language file
./dsh-client-ui-sidebar-documentpreview/lib/client.pdf.js  # false positive: bundled pdf.js font class `static speak(e){return new Speak(e)}`

$ grep -il "audio\|speech" $PKGS/*/package.json
dsh-experimental-api-speech-to-text/package.json
dsh-experimental-client-ui-voice-input/package.json
dsh-experimental-speech-to-text-sensevoice/package.json
dsh-experimental-speech-to-text/package.json
dsh-experimental-voice-input-bundle/package.json
dsh-web-frontend/package.json       # matched only the dependency "@deepseek-ai/dsh-experimental-speech-to-text"

$ grep -rliE "audioOutput|playAudio|AudioContext" --include=*.d.ts $PKGS
./dsh-experimental-client-ui-voice-input/lib/types/client/audio.d.ts   # capture only
```

The one `speak(` hit in `client.pdf.js` is the minified `pdf.js` font-class factory `static speak(e){return new Speak(e)}` (used for `PDFFont` subclasses), unrelated to TTS.

The TTS keyword absent from the entire installed tree, and the stock plugin's README states it outright (`$VOICE/README.md`):
> `- No automatic send, always-on microphone, wake word, streaming captions or speech synthesis.`

`SpeechProvider` is strictly **audio-in → text-out** (`$STT/lib/types/types.d.ts:107-118`):

```ts
export interface SpeechProvider {
    readonly info: SpeechProviderInfo;
    readonly preparation?: SpeechPreparation;
    transcribe(input: SpeechInput, signal: AbortSignal): Promise<Transcript>;
}
```

`SpeechInput` carries `Uint8Array` audio in and `Transcript` text out; there is no `synthesize`/`speak` verb anywhere in the registry (`$STT/lib/types/index.d.ts:40-90` lists exactly: `register`, `listProviders`, `follow`, `snapshot`, `configure`, `prepare`, `cancelPreparation`, `resolve`, `transcribe`).

> If speaking back is required, the integrator must use the **browser's** `window.speechSynthesis` / `SpeechSynthesisUtterance` directly — DSH provides nothing.

### SenseVoice provider installed in this environment

`$PKGS/dsh-experimental-speech-to-text-sensevoice` — "Local SenseVoice ONNX transcription with a managed sherpa-onnx process". Registered id default (`lib/index.js:895, 940-943`):

```js
providerId: z.string().min(1).default("sensevoice-local"),
...
{ id: config.providerId, location: "host-local", languages, ... }
```

It runs `sherpa-onnx-node` in a managed subprocess (`package.json` dependency `sherpa-onnx-node: 1.13.8`), registers via `inject = ["speechToText", "subprocess"]` (`lib/index.js:922`), and is configured in the live profile as `dataRoot: !!js dshHomePath('speech-to-text','sensevoice')`.

---

## (e) Profile wiring actually in effect

`~/.dsh/profiles/web/package.json`:

```jsonc
"dsh": { "profile": { "bundles": [
    "@deepseek-ai/dsh-base",
    "@deepseek-ai/dsh-web-app",
    "dsh-preset-mobile-use",
    "dsh-web-mobile-fix",
    "@deepseek-ai/dsh-experimental-voice-input-bundle"
] } }
```

`~/.dsh/profiles/web/cordis.patch.yml` (live overrides):

```yaml
- id: speech-to-text
  name: "@deepseek-ai/dsh-experimental-speech-to-text"
  config:
    defaultProvider: sensevoice-local
    language: zh
```

Bundle contents — `$PKGS/dsh-experimental-voice-input-bundle/cordis.patch.yml` (verbatim):

```yaml
- insert:
    - id: speech-to-text
      name: '@deepseek-ai/dsh-experimental-speech-to-text'
      config:
        defaultProvider: sensevoice-local
    - id: speech-to-text-sensevoice
      name: '@deepseek-ai/dsh-experimental-speech-to-text-sensevoice'
      config:
        dataRoot: !!js dshHomePath('speech-to-text', 'sensevoice')
    - id: api-speech-to-text
      name: '@deepseek-ai/dsh-experimental-api-speech-to-text'
    - id: ui-voice-input
      name: '@deepseek-ai/dsh-experimental-client-ui-voice-input'
```

`$PKGS/dsh-experimental-voice-input-bundle/package.json` → `"dsh": { "bundle": { "patch": "./cordis.patch.yml" } }`. The bundle's node half is a no-op: `$PKGS/dsh-experimental-voice-input-bundle/lib/index.js` is exactly `export {};`.

So in this profile the chain **host registry → API controller → client-ui plugin** is already complete, and `ctx.remote.speech` will exist.

---

## (f) Microphone permission UX (item 7)

### f.1 The error vocabulary

`$VOICE/lib/types/client/audio.d.ts:3-6`:

```ts
export declare class RecordingError extends Error {
    readonly kind: 'unavailable' | 'permission' | 'empty' | 'cancelled' | 'interrupted';
    constructor(kind: 'unavailable' | 'permission' | 'empty' | 'cancelled' | 'interrupted');
}
```

| Kind | Trigger (file:line) | Stock user-facing copy (en locale) |
|---|---|---|
| `unavailable` | `!navigator.mediaDevices \|\| typeof MediaRecorder === "undefined"` — `$VOICE/lib/client.js:4675` | "current browser does not support recording" (`unavailable`) |
| `permission` | `getUserMedia` rejects with `DOMException.name === "NotAllowedError"` — `:4686` | "Microphone access is off. Allow access in the browser and system settings." (`permission`) |
| `cancelled` | `lifetime.signal.aborted` after the grant — `:4689-4693` (also the abort reason in `release()`, `:4786`) | "Voice input cancelled." (`cancelled`) |
| `interrupted` | `MediaRecorder.onerror` while live — `:4705-4713` | "Recording was interrupted. Try again." (`interrupted`) |
| `empty` | recorder not in `"recording"` state, or zero-byte blob — `:4739-4741`, `:4758` | surfaced via `failed`/`empty` locale keys |
| *(any other throw)* | e.g. `NotFoundError`, `NotReadableError` | `t("failed", { message })` → "Speech recognition failed: {message}" — `$VOICE/lib/client.js:4994` |

The error → copy mapping is a single helper (`$VOICE/lib/client.js:4994`):

```js
const failureText = (failure) => failure instanceof RecordingError ? t(failure.kind) : t("failed", { message: failure instanceof Error ? failure.message : String(failure) });
```

**Key behaviours a custom UI should mirror:**

- **Cancellation must survive a late permission grant.** `getUserMedia` may resolve *after* the user cancelled; the code checks `lifetime.signal.aborted` and immediately stops every track before throwing `cancelled` (`:4689-4694`).
- **Window blur cancels a *recording*, not a *permission request*** (`$VOICE/lib/client.js:4961-4963`):
  ```js
  const blur = () => { if (current.current?.phase === "recording") cancel(); };
  ```
- **Page hide cancels everything except an in-flight transcription** (`:4964-4966`):
  ```js
  const visibility = () => { if (document.hidden && current.current && current.current.phase !== "transcribing") cancel(); };
  ```
- **Escape cancels** (`:4967-4972`); **session change disposes** everything (`useEffect` cleanup on `[sessionId]`, `:4976-4988`, which bumps `generation.current++` so late results are dropped).
- **One shared release promise**: `dispose()` memoizes `release()` (`:4777-4784`) and always closes the `AudioContext` (`:4796-4798`).
- **Duration cap auto-stops**: `setTimeout(() => finish(), maxDurationSeconds * 1e3)` (`:5062-5064`), and the timer overshoot is truncated in `stop()` (`:4761`).
- **Size guard before upload**: `if (audio.byteLength > active.maxAudioBytes) feedback(t("tooLarge"))` (`:5005-5008`).
- **Empty transcript** (`result.value.text === ""`) shows "no speech detected" (`:5018-5021`).

### f.2 The setup dialog

`$VOICE/lib/types/client/VoiceSetupDialog.d.ts:7-12`:

```ts
/**
 * Guide activation or microphone clicks to the existing plugin details.
 * @param props - visibility, installation need and navigation callbacks.
 * @returns a dismissible prompt that never starts preparation or recording.
 */
export declare function VoiceSetupDialog({ open, needsInstallation, onDismiss, onOpenDetails, t }: VoiceSetupDialogProps): import("react").JSX.Element;
```

Opened by the mic button itself when `!usable` (`$VOICE/lib/client.js:5089-5091`), with (`:5097-5098`):

```js
open: setupOpen && !usable,
needsInstallation: readiness.connected && provider?.location === "host-local" && provider.preparation.phase === "unprepared",
```

Footer actions (`:4903-4912`): a ghost "Later" (`onDismiss`) and a primary "Go to setup" / "Open voice plugin settings" with `data-modal-autofocus` (`onOpenDetails`). `onOpenDetails` calls (`$VOICE/lib/client.js:5768-5770`):

```js
openSettings: () => {
    ctx.pluginNavigation.openBundle("@deepseek-ai/dsh-experimental-voice-input-bundle");
},
```

A second, install-prompt variant auto-opens from the bundle activation seat (`$VOICE/lib/client.js:5728-5747`, `VoiceSetupPrompt`), gated on `phase === "unprepared" && provider?.location === "host-local"` and self-dismissing once the phase leaves `checking`.

**Reuse guidance:** the setup dialog does *not* download or prepare anything. If a custom UI needs the same behaviour, either (a) reuse the stock slot occupant and do nothing, or (b) replicate: show a modal when `readiness.connected && provider.location === "host-local" && provider.preparation.phase === "unprepared"`, and route the primary action to `ctx.pluginNavigation.openBundle("<bundle-id>")`. Preparation itself is `ctx.remote.speech.prepare(providerId, {downloadSource?})` and cancellation is `ctx.remote.speech.cancelPreparation(providerId)` — both driven from `plugins.bundle.config` (`$VOICE/lib/client.js:5627, 5635`).

### f.3 Readiness module (what drives every gate)

`$VOICE/lib/types/client/readiness.d.ts:16-19` + `$VOICE/lib/client.js:5383-5422`:

```js
function observeReadiness(ctx) {
    const state = createSnapshotStore({ catalog: null, connected: false, error: null });
    let disposed = false;
    const isDisposed = () => disposed;
    const fail = (error) => {
        state.set({ ...state.getSnapshot(), connected: false, error: error instanceof Error ? error.message : String(error) });
    };
    const stream = ctx.remote.$stream({
        name: "Speech readiness",
        open: (signal) => ctx.remote.speech.follow(signal),
        ended: () => new RemoteStreamCarrierError("Speech readiness stream ended"),
        carrierFailed: fail
    });
    const observing = (async () => {
        try {
            for await (const item of stream) {
                state.set({ catalog: item.value, connected: true, error: null });
                item.accept();
            }
        } catch (error) {
            if (!isDisposed()) fail(error);
        }
    })();
    return { state, dispose: async () => { disposed = true; await stream.dispose(); } };
}
```

`SpeechReadiness` (`readiness.d.ts:6-10`) keeps transport failure **out** of host readiness:

```ts
export interface SpeechReadiness {
    readonly catalog: SpeechCatalog | null;
    readonly connected: boolean;
    readonly error: string | null;
}
```

---

## (g) Live waveform / level mechanism (item 3 in detail)

**There is no streaming audio to the Host and no partial results.** State the negative clearly:

- `MediaRecorder` is started once and stopped once; chunks accumulate in `this.chunks` (`$VOICE/lib/client.js:4702-4704`).
- Exactly one `transcribe()` call happens, after `stop()` returns a complete WAV (`:5009-5012`).
- `speech/transcribe` has **no** `mode: "stream"` in its descriptor (`$SPEECHAPI/lib/typert.remote-client.d.ts:17`); only `speech/follow` is a stream, and it carries `SpeechCatalog` (readiness), not audio or text.

**The live level path is entirely local and 100 % client-side:**

1. One `AnalyserNode` on the mic source (`$VOICE/lib/client.js:4697-4700`):
   ```js
   this.context = new AudioContext();
   this.analyser = this.context.createAnalyser();
   this.analyser.fftSize = this.samples.length;     // 256
   this.context.createMediaStreamSource(stream).connect(this.analyser);
   ```
2. RMS read on demand (`:4724-4730`):
   ```js
   amplitude() {
       if (!this.analyser) return 0;
       this.analyser.getFloatTimeDomainData(this.samples);
       let sum = 0;
       for (const sample of this.samples) sum += sample * sample;
       return Math.sqrt(sum / this.samples.length);
   }
   ```
   → returns a linear RMS in roughly `0 … 1` (unclamped, so loud input can exceed 1).
3. The visualiser polls it from a `requestAnimationFrame` loop with an explicit 50 ms gate, and **mutates SVG attributes directly instead of React state** (`Waveform.js`, `$VOICE/lib/client.js:4842-4888`):
   ```js
   const draw = (now) => {
       if (now - previous >= 50) {
           previous = now;
           let next = recording?.amplitude() ?? 0;
           for (const bar of bars) {
               const previousLevel = bar.level;
               bar.level = next;
               next = previousLevel;
               const height = 1 + Math.min(1, bar.level * 5) * 17;
               bar.element.setAttribute("y1", String(20 - height));
               bar.element.setAttribute("y2", String(20 + height));
           }
       }
       frame = requestAnimationFrame(draw);
   };
   ```
   The shift-register (`next = previousLevel`) scrolls 80 pre-rendered `<line>` bars right-to-left; gain is `×5`, clamped at 1, mapped to 1–18 px half-height.
4. The waveform only renders while `phase === "recording"` (`$VOICE/lib/client.js:5120-5122`):
   ```js
   phase === "recording" ? (0, react_jsx_runtime.jsx)(Waveform, { recording: current.current?.capture, label: t("recording") }) : …
   ```

`Waveform.d.ts` confirms the contract is just `{ recording: Recording | undefined; label: string }` (`$VOICE/lib/types/client/Waveform.d.ts:3-6`), so **any custom UI can call `recording.amplitude()` on its own rAF/timeout loop** — `amplitude()` is public on the `Recording` class (`$VOICE/lib/types/client/audio.d.ts:36-41`).

Recording state machine (from `phase` values in `$VOICE/lib/client.js`, exposed as `data-voice-activity` at `:5110`):
`idle → requesting → recording → transcribing → feedback(idle)`, with `cancel()` returning to `idle` from anywhere.

---

## (h) Explicit UNCERTAINTIES

1. **`Recording` / `encodeWave` / `audioBase64` are not exported.** `$VOICE/package.json` exposes only `.` and `./client`, and `lib/client.js` sets exactly `exports.apply` and `exports.inject` (`:5848-5849`). The recipe in §b.1 is a **re-implementation transcribed from the bundle source**, not an import. If the stock implementation changes between versions, the copy will silently diverge. There is no supported public import path.
2. **`Recording.mimeType` is browser-dependent.** No `mimeType` is passed to `new MediaRecorder(stream)`. On Chromium this is `audio/webm;codecs=opus`; on Firefox/Safari it differs. Since the bytes are decoded and re-rendered to PCM through Web Audio before upload, the wire format is stable — but `decodeAudioData` support for the produced container is the real portability constraint and was not tested here.
3. **Sample rate is fixed at 16 kHz by construction, not by request.** `getUserMedia` constraints contain no `sampleRate`; the resampling happens in `OfflineAudioContext(1, frames, 16000)` (`$VOICE/lib/client.js:4761`). `validateWave` on the host accepts only canonical 16 kHz mono PCM16 (`$STT/lib/types/wave.d.ts:1-8`), so any custom encoder **must** produce that exact format.
4. **`speech` namespace ownership is exclusive.** I verified the rejection logic (`$GATEWAY/lib/client.js:1684-1710`, `1919-1988`) but did **not** execute a runtime double-mount to observe the error string. The check is `if ((this.namespaces.get(descriptor.namespace)?.service)?.has(kind, descriptor.method) === true) throw new Error(\`client api: ${kind} method ${endpointOf(descriptor)} is already mounted\`)` — so a second contribution replaying `speech/transcribe` should throw. A hand-written contribution adding *different* methods to `speech` would pass validation but would still need the Host to expose matching endpoints, which it does not.
5. **Hand-rolling a `TypertRemoteContribution` is untested here.** I verified by reading that client-side codecs are only checked for `codec.mode === "strict"` and never invoked (`$GATEWAY/lib/client.js:2067-2075`), and that `prepareInvocation` reads only `parameter.wire` / `cancellation` / `invocation.kind` (`:1817-1846`). So a minimal literal contribution *should* work, but the stock bundle inlines real zod schemas and I did not run a mount with stub codecs. Treat §b.0's "inline the table" option as unverified; prefer consuming the existing `remote.speech`.
6. **`RemoteStreamHandle` vs `$stream`.** `remote.speech.follow(signal)` returns a raw `RemoteStreamHandle` **for one generation** ("when the carrier is lost, iteration fails with the carrier error and the handle is finished" — `$PKGS/dsh-typert-protocol/lib/types/types.d.ts:90-99`). The stock plugin wraps it in `ctx.remote.$stream({...})` for reconnection (`$VOICE/lib/client.js:5398-5403`). A custom plugin that calls `follow()` directly must implement its own reconnect, or reuse the `$stream` pattern.
7. **`SessionReferenceSource` labels are a TypeScript-only constraint.** `SessionReferenceSourceMap` (`$SESSION/lib/types/client/index.d.ts:17-25`) currently declares only `controllerOperation | gateway` (plus `mainView` merged in by `dsh-client-ui-session`). The runtime does not validate the string (`service.js:188-203` passes `source` straight into `retainScope`), so a plugin can use its own label at runtime and declare it via module augmentation — but I did not find a documented third-party convention for this.
8. **No `clientTimeZone` helper is public.** The object layer computes it via `resolvedClientTimeZone()` (`$SESSION/lib/types/client/sessions/session.js:185`), which is not exported from the package's public subpaths. The recipe substitutes `Intl.DateTimeFormat().resolvedOptions().timeZone`; behaviour equivalence was not verified.
9. **Version lock.** All findings are for `0.2.0-rc.2` of each `@deepseek-ai/dsh-experimental-*` package and the installed `dsh` build (`package.json` version of the voice-input package: `0.2.0-rc.2`). Generated `.d.ts` files carry `/* Generated by @deepseek-ai/dsh-typert-generator from the Host FaceModel — do not edit. */`; a DSH upgrade regenerates them and may change descriptors.
10. **Mobile-context caveat (not investigated).** This environment is an Android/Termux install. Browser `getUserMedia` over `http://127.0.0.1:3080` is a secure context (loopback), so the stock plugin should work — but the README notes "Browser microphone access requires HTTPS or loopback and operating-system permission", and no live device test was performed for this report.
