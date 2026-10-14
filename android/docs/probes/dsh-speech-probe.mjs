#!/usr/bin/env node
/**
 * dsh-speech-probe.mjs — proves capability (4): send a 16 kHz mono PCM16 WAV as
 * base64 to the RUNNING dsh web server and get a transcript back.
 *
 * Run:  node dsh-speech-probe.mjs
 */
const ORIGIN = process.env.DSH_ORIGIN ?? "http://127.0.0.1:3080";

/** Build a canonical 16 kHz mono PCM16 WAV with a simple voiced-ish tone burst. */
function makeWav({ seconds = 1.5, sampleRate = 16000, freq = 180 } = {}) {
  const frames = Math.round(seconds * sampleRate);
  const data = Buffer.alloc(frames * 2);
  for (let i = 0; i < frames; i += 1) {
    const t = i / sampleRate;
    // amplitude-modulated tone so the VAD sees something speech-like
    const env = 0.5 * (1 - Math.cos((2 * Math.PI * Math.min(t, seconds)) / seconds));
    const s = Math.sin(2 * Math.PI * freq * t) * env * 0.6;
    data.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(s * 32767))), i * 2);
  }
  const header = Buffer.alloc(44);
  header.write("RIFF", 0, "ascii");
  header.writeUInt32LE(36 + data.length, 4);
  header.write("WAVE", 8, "ascii");
  header.write("fmt ", 12, "ascii");
  header.writeUInt32LE(16, 16); // fmt chunk size
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28); // byte rate
  header.writeUInt16LE(2, 32); // block align
  header.writeUInt16LE(16, 34); // bits per sample
  header.write("data", 36, "ascii");
  header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

async function rpc(endpoint, args) {
  const body = {
    type: "client-request",
    rpcId: crypto.randomUUID(),
    method: endpoint,
    payload: { args },
  };
  const res = await fetch(`${ORIGIN}/api/${endpoint}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return { status: res.status, json: await res.json() };
}

const wav = makeWav();
const audioBase64 = wav.toString("base64");
console.log(`wav bytes: ${wav.length}  base64 chars: ${audioBase64.length}`);
console.log(`canonical base64 round-trip: ${Buffer.from(audioBase64, "base64").equals(wav)}`);

console.log("\n>>> POST /api/speech/catalog");
const catalog = await rpc("speech/catalog", {});
console.log(`<<< ${JSON.stringify(catalog.json).slice(0, 400)}`);

const args = { request: { audioBase64, providerId: "sensevoice-local", language: "zh" } };
console.log("\n>>> POST /api/speech/transcribe");
console.log(`>>> body (audio elided): ${JSON.stringify({ ...args, request: { ...args.request, audioBase64: `…${audioBase64.length} chars…` } })}`);
const started = Date.now();
const out = await rpc("speech/transcribe", args);
console.log(`<<< HTTP ${out.status} after ${Date.now() - started} ms`);
console.log(`<<< ${JSON.stringify(out.json)}`);
