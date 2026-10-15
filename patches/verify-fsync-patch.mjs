import { readFile } from 'node:fs/promises';
import { open, constants } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const FILE = '/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js';
const src = await readFile(FILE, 'utf8');

// 抽出补丁后的 syncDirectory 真身（含它依赖的 Set 与告警标志）
const start = src.indexOf('const DIRECTORY_FSYNC_UNSUPPORTED');
const marker = '\n}\n';
const end = src.indexOf(marker, src.indexOf('async function syncDirectory(path) {'));
if (start < 0 || end < 0) { console.error('抽不出 syncDirectory，文件结构变了'); process.exit(2); }
const patched = src.slice(start, end + 2);

// 未打补丁的版本（就是原始实现），用来证明这个测试确实能抓到问题
const original = `async function syncDirectory(path) {
  const handle = await open(path, constants.O_RDONLY);
  try { await handle.sync(); } finally { await handle.close(); }
}`;

const HOME = '/data/data/com.termux/files/home/.dsh';

async function walk(syncDirectory, label) {
  const target = resolve(HOME);
  const stop = '/';
  const visited = [];
  let level = target;
  while (level !== stop) {
    const parent = dirname(level);
    try { await syncDirectory(parent); visited.push(parent); }
    catch (e) { return { label, ok: false, at: parent, error: `${e.code}: ${e.message}`, visited }; }
    if (parent === level) break;
    level = parent;
  }
  return { label, ok: true, visited };
}

const make = (code) => new Function('open', 'constants', 'process',
  `${code}\n return syncDirectory;`)(open, constants, process);

for (const [name, code] of [['打补丁前', original], ['打补丁后', patched]]) {
  const r = await walk(make(code), name);
  if (r.ok) console.log(`${name}: ✅ 走完全程（fsync 了 ${r.visited.length} 层，一直上到 /）`);
  else console.log(`${name}: ❌ 在 ${r.at} 抛错 → ${r.error}（走到第 ${r.visited.length} 层）`);
}
