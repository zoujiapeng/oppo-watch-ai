/**
 * Zero-dependency static server for demo/preview.html.
 *
 *   node tools/preview-server.mjs [port]
 *
 * Serves this package directory so the preview can load ../lib/client.js.
 * `localhost` counts as a secure context, so microphone capture also works
 * here without https — the preview performs no network calls of its own.
 */
import { createServer } from "node:http";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { extname, join, normalize, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(fileURLToPath(new URL("..", import.meta.url)));
const PORT = Number(process.argv[2] || 8899);
const SHOTS = join(ROOT, "shots");

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".webmanifest": "application/manifest+json",
};

createServer(async (req, res) => {
  try {
    // Pixel readback sink: the preview composites the WebGL canvases and posts
    // the PNG here, which is how the orb gets inspected on devices whose
    // system screenshot path cannot capture a virtual display.
    if (req.method === "POST" && req.url.startsWith("/shot")) {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = Buffer.concat(chunks);
      await mkdir(SHOTS, { recursive: true });
      const label = new URL(req.url, "http://localhost").searchParams.get("label") || "shot";
      const safe = label.replace(/[^a-zA-Z0-9_.-]/g, "_").slice(0, 60);
      const file = join(SHOTS, `${safe}.png`);
      await writeFile(file, body);
      console.log(`shot ${file} (${body.length} bytes)`);
      res.writeHead(200, { "content-type": "text/plain" }).end(file);
      return;
    }

    const url = new URL(req.url, "http://localhost");

    // Cheap change probe for the preview's self-reload loop. Browsers throttle
    // setTimeout in a headless/virtual display, so the page polls this from its
    // requestAnimationFrame loop instead of transferring the whole bundle.
    if (url.pathname === "/sig") {
      const info = await stat(join(ROOT, "lib", "client.js"));
      res.writeHead(200, {
        "content-type": "text/plain",
        "cache-control": "no-store",
      }).end(`${info.mtimeMs}-${info.size}`);
      return;
    }
    let rel = decodeURIComponent(url.pathname);
    if (rel === "/" || rel.endsWith("/")) rel += "demo/preview.html";
    const target = normalize(join(ROOT, rel));
    if (!target.startsWith(ROOT + sep) && target !== ROOT) {
      res.writeHead(403).end("forbidden");
      return;
    }
    const info = await stat(target);
    if (!info.isFile()) {
      res.writeHead(404).end("not found");
      return;
    }
    const body = await readFile(target);
    res.writeHead(200, {
      "content-type": TYPES[extname(target)] || "application/octet-stream",
      "cache-control": "no-store",
      "content-length": body.length,
    });
    res.end(body);
  } catch (error) {
    res.writeHead(404).end(String(error && error.message));
  }
}).listen(PORT, "127.0.0.1", () => {
  console.log(`preview: http://127.0.0.1:${PORT}/demo/preview.html`);
});
