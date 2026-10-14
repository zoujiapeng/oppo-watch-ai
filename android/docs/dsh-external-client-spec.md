# DSH external-client spec — driving the running `dsh web` instance from a native Android app

**Scope.** How a native Android app (Java, root, same device) can act as a *frontend* of the **already-running** DSH instance at `http://127.0.0.1:3080`, with **zero changes to DSH**.

**Device under test.** Termux at `/data/data/com.termux/files/usr`, Node `v25.3.0`, DSH `@deepseek-ai/dsh@0.2.0-rc.2`. Live server:

```
root 25066 … /data/data/com.termux/files/usr/bin/node --expose-internals \
  /data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --no-open --port 3080
```

**Verdict up front: use Transport A.** It is the only transport that satisfies all five capabilities, it needs no DSH modification, and it is the same path the browser frontend uses. Transport B (`dsh sdk` / `dsh acp`) is a *separate process with a separate agent runtime*, exposes **no speech-to-text at all**, and does **not** stream assistant text token-by-token. Details in §4.

---

## (a) Recommendation

**Transport A — the running web server's HTTP + WebSocket API — is the correct choice.** Reasoning:

| Requirement | Transport A (running `dsh web`) | Transport B (`dsh sdk` / `dsh acp`) |
|---|---|---|
| (1) find or create a session | `session/list` + `session/create` ✅ | sdk: **no list, no create** (session is implicit in `session/prompt`); acp: `session/list`, `session/new` ✅ |
| (2) send a user prompt | `session/prompt` ✅ | `session/prompt` ✅ |
| (3) receive the reply **token-by-token** | ✅ real per-token `text-delta` frames over `/api/remote.mux` | ❌ sdk emits `session.event` only for **durable** events; acp emits `agent_message_chunk` only **after** `assistant/message` commits |
| (4) audio → transcript | ✅ `speech/transcribe` — **verified live, HTTP 200** | ❌ **no speech code exists in either package** (grep for `transcrib|speech` in `dsh-sdk-app`, `dsh-sdk-jsonrpc-server`, `dsh-acp`, `dsh-acp-app` → 0 hits) |
| (5) session list / latest | ✅ `session/list`; live updates via `session/control` | sdk: ❌; acp: ✅ but from disk |
| Drives **the user's running instance** | ✅ literally the same process | ❌ a second process; same files on disk, different in-memory runtime |
| Requires DSH changes | none | none, but requires a new profile dir + `pnpm install` |

The decisive facts are (3) and (4): **only Transport A streams tokens, and only Transport A has speech-to-text.** A secondary but important fact is that Transport A drives the *live* instance — sessions, running agents, tool approvals, and the GUI the user is looking at — whereas Transport B would run a parallel agent that merely shares files.

### Why the "no DSH changes" claim holds even for auth

On **this** device authentication is bypassed by a local patch, but the app must not depend on that. A root app can mint the exact session cookie the server verifies, because the signing secret is readable at `~/.dsh/.credentials.yaml`. This was **proven byte-for-byte** against a live server-issued cookie (§2.1.3). So the app works on a stock, unpatched DSH too.

---

## (b) Byte-exact protocol reference for Transport A

### 2.0 Vocabulary

An RPC **endpoint** is `` `${namespace}/${method}` `` — a **slash**, not a dot. Source:

`…/dsh-api-gateway/lib/index.js:1447`
```js
function endpointOf(namespace, method) {
	return `${namespace}/${method}`;
}
```

So it is `session/list`, **not** `session.list`. The URL path is `/api/<endpoint>`, and the envelope's `method` field must equal that same endpoint string.

### 2.1 Authorization

#### 2.1.1 What the server actually enforces

Every `/api` request passes two gates, both implemented in `@deepseek-ai/dsh-client-connection`.

**Gate 1 — Host/Origin fence** (`isTrustedApiRequest`), `…/dsh-client-connection/lib/index.js:205`:
```js
function isTrustedApiRequest(request, trustedHosts) {
	const host = header$1(request.headers, "host");
	if (host === void 0) return false;
	const hostUrl = parseAuthority(host);
	if (hostUrl === void 0) return false;
	if (!isLoopbackHostname(hostUrl.hostname) && !isTrustedAuthority(hostUrl, trustedHosts)) return false;
	if (header$1(request.headers, "sec-fetch-site") === "cross-site") return false;
	const origin = header$1(request.headers, "origin");
	if (origin === void 0) return true;
	try {
		return new URL(origin).host === hostUrl.host;
	} catch {
		return false;
	}
}
```
`isLoopbackHostname` (`:121`) accepts `localhost`, `[::1]`, and any `127.0.0.0/8` literal. The doc comment at `:140` is explicit that **this is not an auth layer**: *"Network reachability and authentication stay out of scope … this fence is not an auth layer."*

**Verified live:**

| Request | Result |
|---|---|
| `POST /api/session/list`, `Host: evil.example.com` | **403** |
| `POST /api/session/list`, `Origin: http://evil.example.com` | **403** |
| `POST /api/session/list`, `Origin: http://127.0.0.1:3080` | **200** |
| `POST /api/session/list`, `Content-Type: text/plain` | **415** |
| `WS /api/remote.mux`, `Origin: http://evil.example.com` | upgrade **rejected** |

**Gate 2 — browser authentication** (`requestRejection`), `…/dsh-client-connection/lib/index.js:588`:
```js
	requestRejection(request) {
		if (!isTrustedApiRequest(request, this.trustedHosts)) return 403;
		return this.browserAuth.isAuthenticated(request) ? void 0 : 401;
	}
```

#### 2.1.2 The local patch on this device (must be disclosed)

`…/dsh-client-connection/lib/index.js:433`:
```js
	isAuthenticated(request) {
		// [local patch] Auth disabled for a single-user local device.
		return true;
		const authority = requestAuthority(request.headers);
		const rawCookie = header(request.headers, "cookie");
		…
```
The method body returns `true` before the real verification runs. The file's mtime (`Oct 14 20:25`) differs from every other package file (`Oct 14 18:18`), confirming it is a local edit to the installed tree, not upstream behaviour. **The app must not rely on this**: a DSH reinstall/update restores the upstream code and every unauthenticated request becomes `401`.

#### 2.1.3 The credential, and how a local root process obtains it

Upstream verification, `…/dsh-client-connection/lib/index.js:433-445` (the code after the patch), checks a cookie that is an HMAC over a base64url JSON payload:

```js
function signature(secret, body) {
	return createHmac("sha256", secret).update(body).digest();
}
function encodeCookie(payload, secret) {
	const body = encodeBase64Url(Buffer.from(JSON.stringify(payload), "utf8"));
	return `v1.${body}.${encodeBase64Url(signature(secret, body))}`;
}
```
```js
function cookieName(authority) {
	return COOKIE_PREFIX + encodeBase64Url(createHash("sha256").update(authority).digest());
}
```
Payload required (`decodeCookie`, `:322`): `{version: 1, authority, issuedAt, expiresAt}` where `authority` is `new URL('http://'+Host).host` (`requestAuthority`, `:257`) — i.e. literally `127.0.0.1:3080`.

The 32-byte `secret` is a **durable credential record**, `…/dsh-client-connection/lib/index.js:223` and `:325`:
```js
const AUTH_RECORD_KEY = credentialKey("client-connection", "browser-session");
…
async function initializeSecret(credentials) {
	const generated = { version: STORED_SECRET_VERSION, secret: encodeBase64Url(randomBytes(SECRET_BYTES)) };
	const secret = storedSecret(await credentials.modifyRecord(AUTH_RECORD_KEY, (current) => { … }));
```
`@deepseek-ai/dsh-credentials-local` persists records to a `0600` YAML file, `…/dsh-credentials-local/lib/index.js:58`:
```js
		filename: resolve(config.path ?? join(resolveDshHome(config.dshHome), ".credentials.yaml")),
```
On this device that file is `/data/data/com.termux/files/home/.dsh/.credentials.yaml`, and it contains:
```yaml
version: 1
records:
  client-connection/browser-session:
    kind: grant
    payload:
      version: 1
      secret: <32 random bytes, base64url>
```

**Two ways a root app can authenticate.**

**Way 1 — the launch-token exchange (what the browser does).** `dsh web` prints a URL carrying a per-process random token: `…/dsh-client-connection/lib/index.js:374`:
```js
	authenticatedUrl(baseUrl) {
		const url = new URL(baseUrl);
		url.searchParams.set(TOKEN_QUERY, this.launchToken);
		return url.href;
	}
```
`TOKEN_QUERY = "token"` (`:226`); the token is `encodeBase64Url(randomBytes(SECRET_BYTES))` held in a `WeakMap` in-process (`:244`), so it is **only** obtainable from the process's stdout or whatever captured it. On this device: `/data/data/com.termux/files/home/dsh.log` ends with
```
dsh web: http://127.0.0.1:3080/?token=<LAUNCH_TOKEN>
```
A plain `GET /?token=<token>` (exactly one `token` param, `GET`, path `/`) mints the cookie, `…/dsh-client-connection/lib/index.js:394-408`:
```js
			if (req.method === "GET" && url.pathname === "/" && tokens.length === 1 && authority !== void 0 && tokenMatches(tokens.join(""), this.launchToken)) {
				const issuedAt = Date.now();
				const expiresAt = issuedAt + this.maxAgeMilliseconds;
				const value = encodeCookie({ version: COOKIE_PAYLOAD_VERSION, authority, issuedAt, expiresAt }, this.secret);
				res.writeHead(303, {
					"cache-control": "no-store",
					"location": "./",
					"referrer-policy": "no-referrer",
					"set-cookie": sessionCookie(cookieName(authority), value, expiresAt, Math.floor(this.maxAgeMilliseconds / 1e3))
				});
```
Cookie attributes, `…/dsh-client-connection/lib/index.js:296`:
```js
	return `${name}=${value}; Max-Age=${String(maxAgeSeconds)}; Path=/; Expires=${new Date(expiresAt).toUTCString()}; HttpOnly; SameSite=Strict`;
```
**Captured live** (`curl -i "http://127.0.0.1:3080/?token=…"`):
```
HTTP/1.1 303 See Other
cache-control: no-store
location: ./
referrer-policy: no-referrer
set-cookie: dsh-auth-<sha256-authority>=<signed-payload>; Max-Age=2592000; Path=/; Expires=Fri, 13 Nov 2026 13:13:59 GMT; HttpOnly; SameSite=Strict
```
Decoded payload: `{"version":1,"authority":"127.0.0.1:3080","issuedAt":1791983639532,"expiresAt":1794575639532}`.

**Way 2 — mint the cookie from the on-disk secret (recommended for the app).** Read the secret from `~/.dsh/.credentials.yaml` and compute `encodeCookie` + `cookieName` locally. **This was verified to reproduce the server's own bytes exactly:**

```
secret bytes: 32
payload: {"version":1,"authority":"127.0.0.1:3080","issuedAt":1791983639532,"expiresAt":1794575639532}
server sig: <hmac-signature>
my     sig: <hmac-signature>
MATCH: true
cookie name: dsh-auth-<sha256-authority>  (expected: same)  true
```

Advantages: no log scraping, no dependence on the token surviving a restart, valid for `cookieMaxAgeDays` (default 30, `:804`), and re-mintable at will. Set `authority` to exactly the `Host` header value your requests use (`127.0.0.1:3080`), and `issuedAt <= now < expiresAt` with `expiresAt - issuedAt <= 30 days`.

> **Privacy note:** the same file also contains `refs: DEEPSEEK_API_KEY: sk-…`. The app should parse only the `client-connection/browser-session` record and must never log or transmit the file.

#### 2.1.4 Request headers the app must send

| Header | Value | Why |
|---|---|---|
| `Host` | `127.0.0.1:3080` | Gate 1. `HttpURLConnection` sets this automatically from the URL — that is fine. |
| `Content-Type` | `application/json` | Enforced at `…/dsh-client-connection/lib/index.js:681`; anything else → **415**. |
| `Cookie` | `dsh-auth-<b64url(sha256("127.0.0.1:3080"))>=v1.<payload>.<sig>` | Gate 2 (upstream). Omit to work on this patched device, but then the app breaks on reinstall. |
| `Origin` | **omit it** | If present it must equal `http://127.0.0.1:3080`; a mismatch is **403**. `HttpURLConnection` does not send `Origin`. |
| `Sec-Fetch-Site` | never `cross-site` | Same gate. |

### 2.2 Unary RPC: HTTP request/response envelope

Registration: the `/api` prefix route, `…/dsh-client-connection/lib/index.js:832-845`. Per-request handling, `rpcFetchHandler`, `…/dsh-client-connection/lib/index.js:675`:

```js
function rpcFetchHandler(channel, handler, peer) {
	return {
		requestBodyMode: () => "buffered",
		async fetch(request) {
			const endpoint = endpointFromPath(channel, new URL(request.url).pathname);
			if (request.method !== "POST" || endpoint === void 0) return new Response("not found", { status: 404 });
			if (request.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase() !== "application/json") return new Response("content type must be application/json", { status: 415 });
			let body;
			try { body = await request.json(); } catch { return new Response("body is not JSON", { status: 400 }); }
			const envelope = clientRequestSchema.safeParse(body);
			if (!envelope.success) return invalidEnvelopeResponse(body, envelope.error.issues);
			const message = envelope.data;
			if (message.method !== endpoint) return errorResponse(message.rpcId, {
				code: "gateway/bad-request",
				message: `method ${JSON.stringify(message.method)} does not match endpoint ${JSON.stringify(endpoint)}`,
				details: { issues: [] }
			});
			try {
				const result = await handler(endpoint, message.payload, request.signal, peer);
				return fullResponse(message.rpcId, result);
			} catch (error) {
				return new Response(`handler failure: ${String(error)}`, { status: 500 });
			}
		}
	};
}
```

Envelope schemas, `…/dsh-client-connection/lib/index.js:506-517`:
```js
const clientRequestSchema = z$1.object({
	type: z$1.literal("client-request"),
	rpcId: rpcIdSchema,
	method: z$1.string(),
	payload: z$1.unknown()
});
const serverResponseSchema = z$1.object({
	type: z$1.literal("server-response"),
	rpcId: rpcIdSchema,
	result: rpcResultSchema(z$1.unknown().optional())
});
```
```js
const rpcErrorSchema = z$1.object({
	code: z$1.string(),
	message: z$1.string(),
	details: z$1.record(z$1.string(), z$1.unknown())
});
```

**Request**
```
POST /api/<namespace>/<method> HTTP/1.1
Host: 127.0.0.1:3080
Content-Type: application/json
Cookie: dsh-auth-…=v1.…   (upstream only)

{"type":"client-request","rpcId":"<any unique string>","method":"<namespace>/<method>","payload":{"args":{ …named args… }}}
```
The `args` object is keyed by each method's **wire parameter name** (see §2.4) — this is the single most common mistake; a wrong name yields `gateway/arguments-invalid`.

**Success** (`fullResponse`, `…/dsh-client-connection/lib/index.js:725`):
```json
{"type":"server-response","rpcId":"<echoed>","result":{"ok":true,"value": <method result>}}
```
**Endpoint failure** (`errorResponse` → the same 200-OK envelope):
```json
{"type":"server-response","rpcId":"<echoed>","result":{"ok":false,"error":{"code":"gateway/…","message":"…","details":{…}}}}
```
Note: **RPC failures still return HTTP 200** — the app must inspect `result.ok`. HTTP status is used only for transport faults: `403` fence, `401` auth, `404` unknown endpoint, `415` bad content-type, `400` non-JSON body, `500` handler crash, `413` body over `maxRequestBodyBytes` (default `300 MiB`, `:25`).

**Real captured exchange** (from the probe run):
```
>>> POST http://127.0.0.1:3080/api/session/list
>>> body: {"type":"client-request","rpcId":"66965764-…","method":"session/list","payload":{"args":{"_request":{}}}}
<<< HTTP 200 application/json
<<< {"type":"server-response","rpcId":"66965764-…","result":{"ok":true,"value":{"items":[{"sessionId":"session-994b0271-…","updatedAt":1791983723802,"agentAvailable":true,"running":false,"blank":false,"cwd":"/data/data/com.termux/files/home/oppo-watch-ai","projections":{…}}]}}}
```

### 2.3 Streaming: the WebSocket mux

**URL:** `ws://127.0.0.1:3080/api/remote.mux` — `…/dsh-api-gateway/lib/index.js:12`:
```js
const REMOTE_STREAM_MUX_PATH = "/api/remote.mux";
```
**Handshake:** plain WebSocket upgrade. **No subprotocol is negotiated** — the acceptor is `new WebSocketServer({ noServer: true })` with no `handleProtocols` (`…/dsh-api-gateway/lib/index.js:179`), and the browser client connects with `new WebSocket(remoteStreamUrl())` with no protocol list (`…/dsh-api-gateway/lib/client.js:507`). The upgrade goes through the *same* gates as HTTP (`…/dsh-api-gateway/lib/index.js:634`):
```js
						handler: (req, socket, head) => {
							const admission = webCtx.connection.admit(req);
							if ("rejection" in admission) {
								rejectRemoteStreamUpgrade(socket, admission.rejection);
								return;
							}
							mux.handleUpgrade(req, socket, head, admission.peer);
						}
```
**Framing:** JSON **text** frames only; binary closes the socket with code 1003 (`…/dsh-api-gateway/lib/index.js:276`). The server sends WebSocket pings and terminates after 2 missed pongs (`MAX_MISSED_HEARTBEATS = 2`, `…/dsh-api-gateway/lib/index.js:172`).

**Client → Host** (`parseRemoteStreamClientMessage`, `…/dsh-api-gateway/lib/index.js:115`):
```js
			if (value.type === "item" && (exactKeys(value, ["type","streamId"]) || exactKeys(value, ["type","streamId","value"])) && validId(value.streamId) && (!Object.hasOwn(value, "value") || isRemoteJsonValue(value.value))) return value;
			if (value.type === "open" && exactKeys(value, ["type","streamId","endpoint","payload"]) && validId(value.streamId) && typeof value.endpoint === "string" && value.endpoint.length > 0) return value;
```
plus `{"type":"end","streamId"}` and `{"type":"cancel","streamId"}`. `payload` has the **same shape as the HTTP `payload`**, i.e. `{"args":{…}}` — the browser does `this.openRemoteStream(endpoint, { args: prepared.args }, …)` (`…/dsh-api-gateway/lib/client.js:1815`).

**Host → Client** (`parseRemoteStreamServerMessage`, `…/dsh-api-gateway/lib/client.js:160-175`):
```js
				if (value.type === "item" && (exactKeys(value, ["type","streamId"]) || exactKeys(value, ["type","streamId","value"])) && validId(value.streamId)) return value;
				if (value.type === "end" && exactKeys(value, ["type","streamId"]) && validId(value.streamId)) return value;
				if (value.type === "error" && exactKeys(value, ["type","streamId","error"]) && … exactKeys(value.error, ["code","message","details"]) …) return value;
```
**Multiplexing:** many logical streams share one socket, keyed by a client-generated `streamId`. A second `open` with a live `streamId` is a protocol violation and closes the socket (`…/dsh-api-gateway/lib/index.js:316`). Client-generated UUIDs make collisions impossible in practice.

**Real captured stream** (probe):
```
>>> WS ws://127.0.0.1:3080/api/remote.mux
>>> frame: {"type":"open","streamId":"probe-1","endpoint":"session/follow","payload":{"args":{"request":{"address":{"kind":"session","sessionId":"session-ef420c32-…"},"assistantStream":true}}}}
<<< WS item: snapshot cursor=2 header.id=session-ef420c32-… records=3 hasMore=false
```

### 2.4 The five capabilities — exact endpoints

#### (5) Read the session list / latest session — `session/list`

Descriptor (`…/dsh-api-remotes/lib/client.js:11447`):
```js
					id: "@deepseek-ai/dsh-api-session-controller#session/list",
					namespace: "session",
					method: "list",
					invocation: { kind: "direct" },
					parameters: [{
						name: "_request",
						wire: "_request",
						source: "json",
						codec: { mode: "strict", typeSymbol: "…types#SessionListRequest", … }
					}],
					cancellation: { parameter: "signal" },
```
**The argument name is literally `_request`** (underscore — the source signature is `list(_request, signal)`).

```
POST /api/session/list
{"type":"client-request","rpcId":"…","method":"session/list","payload":{"args":{"_request":{}}}}
```
→ `value: {items: SessionSummary[]}` (`…/dsh-api-session-controller/lib/types/types.d.ts:262`):
```ts
export interface SessionListRequest { readonly cursor?: string; }
export interface SessionListValue { readonly items: readonly SessionSummary[]; }
export interface SessionSummary {
    readonly agentAvailable: boolean;
    readonly sessionId: SessionId;
    readonly updatedAt: number;
    readonly running: boolean;
    readonly blank: boolean;
    readonly parentSessionId?: SessionId;
    readonly origin?: 'subagent';
    readonly cwd?: string;
    readonly projections?: SessionProjectionHints;
}
```
Ordering is **newest-first** (verified live). `projections.asOfSeq` is the current durable cursur; `projections.values.title` is the conversation title; `running` tells whether the agent is mid-turn. Filter `origin === "subagent"` to get root conversations only.

**Live session-list updates (bonus, verified):** `session/control` is a stream taking **no arguments** — open it with `payload:{"args":{}}`. Frames are `SessionControlFrame` (`…/dsh-api-session-controller/lib/types/types.d.ts:540`): one `{"type":"baseline","value":{"projections":{…}}}` followed by `{"type":"projection","sessionId","key","value","seq"}` deltas. Confirmed live: 21 items in 10 s on the running host.

#### (1) Create a session — `session/create`

Descriptor `…/dsh-api-remotes/lib/client.js:11346`: parameter `name: "request", wire: "request"`, no `mode` ⇒ **unary**.
```ts
export interface SessionCreateRequest {
    readonly workspaceId?: WorkspaceId;
    readonly cwd?: string;
    readonly sessionId?: SessionId;
    readonly agentPreset?: string;
}
export interface SessionCreateValue { readonly sessionId: SessionId; readonly agentPreset?: string; }
```
`workspaceId` and `cwd` are mutually exclusive (`…/dsh-api-session-controller/lib/index.js:687`: `"session.create accepts workspaceId or cwd, not both"`).
```
POST /api/session/create
{"type":"client-request","rpcId":"…","method":"session/create","payload":{"args":{"request":{"cwd":"/data/data/com.termux/files/home/oppo-watch-ai"}}}}
```
**Real captured response:**
```json
{"type":"server-response","rpcId":"5be0928f-…","result":{"ok":true,"value":{"sessionId":"session-ef420c32-a250-45c4-b674-cf9b4e63b420","agentPreset":"mobile-use"}}}
```
A **new session's first prompt costs a full system-prompt + tools turn.** To target "the current conversation" instead, reuse an existing `sessionId` from `session/list`.

#### (2) Send a prompt — `session/prompt`

Descriptors `…/dsh-api-remotes/lib/client.js:11577`: `name: "request", wire: "request"`.
```ts
export interface SessionPromptRequest {
    /** Client-minted identity persisted on the exact accepted user message. */
    readonly requestId: SessionRequestId;
    readonly sessionId: SessionId;
    readonly mode: 'queue' | 'steer';
    /** At least one non-whitespace text part or attachment. */
    readonly content: readonly PromptContentPart[];
    readonly clientTimeZone?: string;
}
export interface SessionPromptValue { readonly accepted: true; }
export type PromptContentPart =
    | { readonly type: 'text'; readonly text: string }
    | { readonly type: 'image'; readonly mediaType: ImageMediaType; readonly data: string; readonly name?: string }
    | { readonly type: 'file'; readonly receiptId: Branded<'file-upload-receipt-id'> };
```
```
POST /api/session/prompt
{"type":"client-request","rpcId":"…","method":"session/prompt","payload":{"args":{"request":{
  "requestId":"0262ce18-e589-4090-a373-4ced6b8aa884",
  "sessionId":"session-ef420c32-…",
  "mode":"queue",
  "content":[{"type":"text","text":"只回复：协议正常"}]
}}}}
```
**Real captured response:** `{"ok":true,"value":{"accepted":true}}`.

Semantics: `mode:"queue"` appends after the current turn; `"steer"` injects into the running turn. `requestId` is a **client-minted** id, echoed back on the durable `user/message` as `data.source.rpcId` — use it to correlate your optimistic bubble with the stored message. `accepted:true` means *admitted to the agent inbox*, **not** that the reply exists; the reply arrives on the `session/follow` stream.

Related: `session/cancel` `{request:"request":{sessionId}}` to abort a turn.

#### (3) Stream the reply — `session/follow` (stream mode)

Descriptor `…/dsh-api-remotes/lib/client.js:11373`: `mode: "stream"`, parameter `wire: "request"`, `cancellation: { parameter: "signal" }`.
```ts
export interface SessionFollowRequest extends Pick<SessionPageRequest, 'maxMessages' | 'turnWindow'> {
    readonly address: SessionAddress;
    /** Include process-local assistant presentation frames for the Web client. */
    readonly assistantStream?: true;
}
export type SessionAddress =
  | { readonly kind: 'session'; readonly sessionId: SessionId }
  | { readonly kind: 'subagent'; readonly parentSessionId: SessionId; readonly childSessionId: SessionId; readonly mode: 'one-shot' | 'continuable' | 'unknown' };
```
**`assistantStream: true` is what turns on token-by-token delivery.** Frame union (`…/dsh-api-session-controller/lib/types/types.d.ts:516`):
```ts
export type SessionFollowFrame =
  | { readonly type: 'snapshot'; readonly header: SessionWireHeader; readonly cursor: number;
      readonly records: readonly SessionHistoryRecord[]; readonly hasMore: boolean;
      readonly projections: SessionProjectionBaseline;
      readonly assistantStream?: SessionAssistantStreamBaseline }
  | SessionEventEntry                                   // { type:'event', event: SessionWireEvent }
  | { readonly type: 'assistant-stream'; readonly frame: SessionAssistantStreamFrame };
```
```ts
export type SessionAssistantStreamFrame =
  | { type:'start';  attemptId; revision; startedAfterSeq; turn; step }
  | { type:'chunk';  attemptId; revision; index; time; chunk: JsonValue }
  | { type:'end';    attemptId; revision; index; outcome: {kind:'committed'; eventType; seq} | {kind:'abandoned'} };
```
and `chunk` is an LLM `StreamChunk` (`…/dsh-llm/lib/types/types.d.ts:417`):
```ts
export type StreamChunk =
  | { type: 'block-start'; index: number; blockType: ContentBlockType }
  | { type: 'text-delta'; index: number; text: string }
  | { type: 'reasoning-delta'; index: number; text: string }
  | { type: 'tool-call-delta'; index: number; id: ToolCallId; name?: string; argumentsDelta: string }
  | { type: 'block-end'; index: number; block: ContentBlock }
  | { type: 'usage'; usage: TokenUsage }
  | { type: 'finish'; reason: FinishReason; replayState?: ReplayEnvelope };
```

**Real captured wire frames** (verbatim `value` of `{"type":"item","streamId":"probe-1","value":…}`):
```json
{"type":"start","attemptId":"session-ef420c32-a250-45c4-b674-cf9b4e63b420:1","revision":1,"turn":1,"step":1,"startedAfterSeq":11}
{"type":"chunk","attemptId":"session-ef420c32-…:1","revision":2,"index":0,"time":1791983732960,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}}
{"type":"chunk","attemptId":"session-ef420c32-…:1","revision":3,"index":1,"time":1791983733694,"chunk":{"type":"block-end","index":0,"block":{"type":"reasoning","text":""}}}
{"type":"chunk","attemptId":"session-ef420c32-…:1","revision":5,"index":3,"time":1791983733697,"chunk":{"type":"text-delta","index":1,"text":"协议"}}
{"type":"chunk","attemptId":"session-ef420c32-…:1","revision":8,"index":6,"time":1791983733701,"chunk":{"type":"usage","usage":{"inputTokens":172,"outputTokens":3,"cacheReadTokens":7424,"cacheWriteTokens":0,"totalTokens":7599}}}
{"type":"chunk","attemptId":"session-ef420c32-…:1","revision":9,"index":7,"time":1791983733702,"chunk":{"type":"finish","reason":{"kind":"stop"},"replayState":{…}}}
```
Note `text":"协议"` followed by a separate delta for `"正常"` — genuine token-by-token delivery. **Render only `chunk.type === "text-delta"` into the visible bubble; ignore `reasoning-delta` (or route it to a "thinking" area).**

The **authoritative final text** comes from the durable event, not from the concatenated deltas:
```json
{"type":"event","event":{"type":"assistant/message","seq":17,"time":…,"surfaceOp":"append",
  "data":{"turn":1,"step":1,"message":{"role":"assistant","id":"…","source":{…},
    "content":[{"type":"reasoning","text":""},{"type":"text","text":"协议正常"}]},
    "stream":[…],"usage":{…}}}}
```
The turn is over when you see `assistant-stream` `{"type":"end",…,"outcome":{"kind":"committed","eventType":"assistant/message","seq":17}}` (or the `assistant/message` event itself). Use this, **not** `session/list.running`, to detect turn completion.

#### (4) Speech-to-text — `speech/transcribe`

Namespace `speech`, service `speechController` (`…/dsh-experimental-api-speech-to-text/lib/index.js:141`):
```js
			super(ctx, "speechController", { namespace: "speech" });
```
```ts
export interface TranscriptionRequest {
    readonly audioBase64: string;
    readonly providerId?: SpeechProviderId;
    readonly language?: string;
}
export interface SpeechCatalog extends SpeechSnapshot {
    readonly maxAudioBytes: number;      // 4 MiB default
    readonly maxDurationSeconds: number; // 120 s default
}
```
Handler + validation (`…/dsh-experimental-api-speech-to-text/lib/index.js`, `transcribe`):
```js
		async transcribe(request, signal) {
			signal.throwIfAborted();
			const encoded = request.audioBase64;
			if (encoded.length > Math.ceil(this.config.maxAudioBytes / 3) * 4) throw new RemoteError("speech/invalid-audio", "Audio is invalid or exceeds the configured byte limit", { reason: "encoding-or-size" });
			const audio = Buffer.from(encoded, "base64");
			try {
				if (audio.toString("base64") !== encoded) throw new Error("Audio must use canonical base64 encoding");
				if (audio.length > this.config.maxAudioBytes) throw new Error("Audio exceeds the configured byte limit");
				validateWave(audio, this.config.maxDurationSeconds);
				…
				return await this.ctx.speechToText.transcribe(spec, signal);
			} catch (error) {
				throw new RemoteError("speech/transcription-failed", reason, { reason });
			}
```
Result type (`…/dsh-experimental-speech-to-text/lib/types/types.d.ts:102`):
```ts
export interface Transcript {
    readonly text: string;
    readonly audioSeconds: number;
    readonly inferenceSeconds: number;
}
```
```
POST /api/speech/transcribe
{"type":"client-request","rpcId":"…","method":"speech/transcribe","payload":{"args":{"request":{
  "audioBase64":"<canonical base64 of a 16 kHz mono PCM16 WAV>",
  "providerId":"sensevoice-local",
  "language":"zh"
}}}}
```
**Hard validation rules:** base64 must be **canonical** (re-encoding must reproduce the identical string), the payload must be a valid **RIFF/WAVE** file, and `audio.length <= maxAudioBytes`. Errors are `speech/invalid-audio` (encoding/size) or `speech/transcription-failed` (`{reason}`).

**Live proof (real output):**
```
wav bytes: 48044  base64 chars: 64060
canonical base64 round-trip: true

>>> POST /api/speech/catalog
<<< {"ok":true,"value":{"providers":[{"id":"sensevoice-local","name":"SenseVoiceSmall (INT8)","location":"host-local",
      "languages":["auto","zh","en","yue","ja","ko"],…,"preparation":{"phase":"standby",…}}],
      "selection":{"providerId":"sensevoice-local","language":"zh"},
      "maxAudioBytes":4194304,"maxDurationSeconds":120}}

>>> POST /api/speech/transcribe
>>> body (audio elided): {"request":{"audioBase64":"…64060 chars…","providerId":"sensevoice-local","language":"zh"}}
<<< HTTP 200 after 92 ms
<<< {"type":"server-response","rpcId":"5294ce4b-…","result":{"ok":true,"value":{"text":"","audioSeconds":1.5,"inferenceSeconds":0.025023124999992433}}}
```
The transcript is empty **by design** — the probe's synthetic audio is a 180 Hz tone, not speech, and the type documents *"an empty string means no speech was recognized."* The round trip (canonical-base64 check → WAV validation → provider dispatch → model inference → typed result) is fully exercised. The app should send real 16 kHz mono PCM16 audio and omit `providerId`/`language` to use the profile's configured defaults — this profile sets `defaultProvider: sensevoice-local, language: zh` in `~/.dsh/profiles/web/cordis.patch.yml`.

`speech/catalog` (no arguments — send `payload:{"args":{}}`) returns providers, readiness (`providers[].preparation.phase`, `"standby"` = loaded), current selection, and the intake limits; call it once to size the recorder.

#### Other endpoints the app may want

Full list of namespaces discovered in the generated client (`…/dsh-api-remotes/lib/client.js`), with `session` expanded:

```
session: attachment, canOpenWorkspacePath, cancel, control, create, follow, fork,
         initializeDefaultModel, list, modelCatalog, openWorkspacePath, page,
         projections, prompt, rename, search, selectModel, updateQueue, workspacePathApplications
pluginManager, dynamicCordisRunner, workspace, account, terminal, goals, workspaceFiles,
settings, schedule, productAnalytics, messageFeedback, llm, job, directoryPicker,
credentials, agentPresets, userQuestions, subagents, officeToPdf, commands, skills,
sessionReferenceResolver, sessionFeedback, pluginRegistryProbe, pluginInventory,
permissionPresets, fileUploads, fileReferences
```
Plus the two stream-only ones outside the generated table, resolved by source-mode discovery (`claimsEndpoint`, `…/dsh-api-gateway/lib/index.js:698`): `speech/*` and `$events`.

**`session/page`** (the polling fallback, currently used by `DshHttpTransport.java`) is a unary read of history:
```ts
export interface SessionPageRequest {
    readonly address: SessionAddress;
    /** Inclusive log cut obtained from the corresponding follow opening frame. */
    readonly throughSeq: number;
    readonly beforeSeq?: number;
    readonly maxMessages?: number;
    readonly turnWindow?: { readonly minMessages: number; readonly minTurns: number };
}
export interface SessionPage { readonly records: readonly SessionHistoryRecord[]; readonly hasMore: boolean; }
```
Verified live: `{"args":{"request":{"address":{"kind":"session","sessionId":"…"},"throughSeq":17,"maxMessages":3}}}` returned records `[{type:"event",event:{type:"user/message",seq:8,…}}, …]`. Keep this as the **cold-start / reconnect** path — the `snapshot` frame of `session/follow` already contains the same records, so a correct WS client rarely needs it.

---

## (c) The working probe script and its real output

**Script:** `/data/data/com.termux/files/home/oppo-watch-ai/dsh-api-probe.mjs` (zero npm dependencies, uses Node's global `fetch` and global `WebSocket`; Node ≥ 22).

It performs, in order: `session/list` (prints the raw request/response) → `session/create` → WebSocket `open` of `session/follow` with `assistantStream:true` → `session/prompt` with text exactly `只回复：协议正常` → prints each `text-delta` as it arrives → prints the final durable `assistant/message` text.

**Real output of the final run** (linker warnings stripped):

```
=== DSH Transport-A probe ===
origin: http://127.0.0.1:3080  node: v25.3.0

>>> POST http://127.0.0.1:3080/api/session/list
>>> content-type: application/json
>>> body: {"type":"client-request","rpcId":"66965764-b78c-4fdc-876a-ddff74aaa946","method":"session/list","payload":{"args":{"_request":{}}}}
<<< HTTP 200 application/json
<<< body: {"type":"server-response","rpcId":"66965764-b78c-4fdc-876a-ddff74aaa946","result":{"ok":true,"value":{"items":[{"sessionId":"session-994b0271-dc1b-486c-9cac-70325f78fc03","updatedAt":1791983723802,"agentAvailable":true,"running":false,"blank":false,"cwd":"/data/data/com.termux/files/home/oppo-watch-ai","projections":{"kind":"sequenced","asOfSeq":19,"values":{"title":"协议正常",…}}}]}}}…(39064 bytes)

[1] session/list -> 21 sessions
    session-994b0271-dc1b-486c-9cac-70325f78fc03  running=false updatedAt=1791983723802 title="协议正常"
    session-ba6e59e8-889f-48c9-a057-e90016d31bfb  running=false updatedAt=1791983540684 title="协议测试回复正常"
    session-a6802ecd-5d9d-4743-b91e-c0a492548359  running=false updatedAt=1791983509945 title="语音链路正常"
    8db17807-bd9e-4c2b-8384-994fe7253ddd  running=true  updatedAt=1791983306486 title="You are reverse-engineering the DeepSeek"
    session-8da8408b-ea9a-44e4-b5cf-4da88e088698  running=true  updatedAt=1791983120814 title="Siri液态玻璃前端接入dsh插件"

>>> POST http://127.0.0.1:3080/api/session/create
>>> body: {"type":"client-request","rpcId":"5be0928f-4f5e-48fc-b156-70aa0e744496","method":"session/create","payload":{"args":{"request":{"cwd":"/data/data/com.termux/files/home/oppo-watch-ai"}}}}
<<< HTTP 200 application/json
<<< body: {"type":"server-response","rpcId":"5be0928f-4f5e-48fc-b156-70aa0e744496","result":{"ok":true,"value":{"sessionId":"session-ef420c32-a250-45c4-b674-cf9b4e63b420","agentPreset":"mobile-use"}}}

[2] session/create -> session-ef420c32-a250-45c4-b674-cf9b4e63b420

>>> WS ws://127.0.0.1:3080/api/remote.mux
>>> frame: {"type":"open","streamId":"probe-1","endpoint":"session/follow","payload":{"args":{"request":{"address":{"kind":"session","sessionId":"session-ef420c32-a250-45c4-b674-cf9b4e63b420"},"assistantStream":true}}}}

<<< WS item: snapshot cursor=2 header.id=session-ef420c32-a250-45c4-b674-cf9b4e63b420 records=3 hasMore=false

>>> POST http://127.0.0.1:3080/api/session/prompt
>>> body: {"type":"client-request","rpcId":"fb0b0f31-b6c1-47cf-8943-09d81677cf57","method":"session/prompt","payload":{"args":{"request":{"requestId":"4af3eb3a-ada9-4d8c-89b8-8ebabd8b822f","sessionId":"session-ef420c32-a250-45c4-b674-cf9b4e63b420","mode":"queue","content":[{"type":"text","text":"只回复：协议正常"}]}}}}
<<< HTTP 200 application/json
<<< body: {"type":"server-response","rpcId":"fb0b0f31-b6c1-47cf-8943-09d81677cf57","result":{"ok":true,"value":{"accepted":true}}}

[4] session/prompt accepted at 2026-10-14T13:15:32.099Z
<<< durable event seq=8 type=user/message
<<< durable event seq=9 type=user/message

<<< assistant-stream start attempt=session-ef420c32-a250-45c4-b674-cf9b4e63b420:1 turn=1 step=1
>>> 协议正常
<<< durable event seq=17 type=assistant/message turn=1 step=1
<<< message.content types: ["reasoning","text"]
<<< usage: {"inputTokens":172,"outputTokens":3,"cacheReadTokens":7424,"cacheWriteTokens":0,"totalTokens":7599}


===== RESULT =====
sessionId            : session-ef420c32-a250-45c4-b674-cf9b4e63b420
streamed text deltas : "协议正常"
final assistant text : "协议正常"
prompt sent          : "只回复：协议正常"
match                : YES

raw assistant-stream frames observed (wire values of {"type":"item","value":…}):
  {"type":"start","attemptId":"session-ef420c32-a250-45c4-b674-cf9b4e63b420:1","revision":1,"turn":1,"step":1,"startedAfterSeq":11}
  {"type":"chunk",…,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}}
  {"type":"chunk",…,"chunk":{"type":"block-end","index":0,"block":{"type":"reasoning","text":""}}}
  {"type":"chunk","attemptId":"session-ef420c32-…:1","revision":5,"index":3,"time":1791983733697,"chunk":{"type":"text-delta","index":1,"text":"协议"}}
  {"type":"chunk",…,"chunk":{"type":"usage","usage":{"inputTokens":172,"outputTokens":3,"cacheReadTokens":7424,"cacheWriteTokens":0,"totalTokens":7599}}}
  {"type":"chunk",…,"chunk":{"type":"finish","reason":{"kind":"stop"},"replayState":{…}}}
```

`match: YES` — the streamed deltas and the durable assistant message both equal the expected `协议正常`.

**Supporting probe:** `/data/data/com.termux/files/home/oppo-watch-ai/dsh-speech-probe.mjs` produced the `speech/transcribe` output quoted in §2.4(4).

---

## (d) How a Java/Android client implements this

The app already has a working skeleton at `android/src/com/glslplayground/app/DshHttpTransport.java`. It uses **HTTP polling of `session/page` every 850 ms** to fake streaming. Replace that with the WebSocket mux; keep the HTTP layer for unary calls.

### Minimal HTTP layer (keep, extend)

**One-time auth (`authenticate()`).** Preferred, patch-independent path:
1. Root-read `~/.dsh/.credentials.yaml` (fallback `su -c "cat …"`).
2. Extract `records["client-connection/browser-session"].payload.secret` (base64url, 32 bytes). Do **not** use a general YAML parser on the whole file if you can avoid it — parse only that block, and never log the file.
3. `authority = "127.0.0.1:3080"`.
4. `name = "dsh-auth-" + base64url(sha256(authority))` (strip `=`).
5. `now = System.currentTimeMillis()`; `body = base64url("{\"version\":1,\"authority\":\"…\",\"issuedAt\":"+now+",\"expiresAt\":"+(now+30L*86400000)+"}")`.
6. `sig = base64url(HmacSHA256(secretBytes, body))`.
7. `cookie = name + "=v1." + body + "." + sig`.
8. Fallback if the file is unreadable: scrape `token=` from `dsh.log` / `dsh-restart.log` / `dsh-watchdog.log` and `GET /?token=<token>` with `setInstanceFollowRedirects(false)`, then take `Set-Cookie` up to the first `;`.

**Important Java detail:** `HttpURLConnection` sets `Host` from the URL (correct, `127.0.0.1:3080`) and does **not** send `Origin` — exactly what the fence wants. Send the cookie via `c.setRequestProperty("cookie", cookie)`.

**Unary call (`rpc(endpoint, args)`)** — build with `org.json`:

```java
JSONObject envelope = new JSONObject();
envelope.put("type", "client-request");
envelope.put("rpcId", "android-" + (++seq));
envelope.put("method", endpoint);          // "session/list" — SLASH
JSONObject payload = new JSONObject();
payload.put("args", args);                  // args keyed by the method's WIRE name
envelope.put("payload", payload);
// POST to base + "/api/" + endpoint
// header: content-type: application/json ; cookie: <above>
```
Parse the reply: `res.getJSONObject("result")`; if `!result.optBoolean("ok")` read `result.getJSONObject("error")` (`code`, `message`, `details`) and surface `message`. Retry once on `401`/`403` after re-authenticating, and on `404` after a short sleep (server restarting).

Argument-carrying rules that trip people up:

| Endpoint | `args` to build |
|---|---|
| `session/list` | `{"_request":{}}` — **underscore required** |
| `session/create` | `{"request":{"cwd":"<abs path>"}}` |
| `session/prompt` | `{"request":{"requestId":<uuid>,"sessionId":<id>,"mode":"queue","content":[{"type":"text","text":<prompt>}]}}` |
| `session/page` | `{"request":{"address":{"kind":"session","sessionId":<id>},"throughSeq":<cursor>,"maxMessages":40}}` |
| `session/cancel` | `{"request":{"sessionId":<id>}}` |
| `speech/catalog` | `{}` |
| `speech/transcribe` | `{"request":{"audioBase64":<canonical b64 WAV>,"language":"zh"}}` |

### Minimal WebSocket layer (new)

`org.java_websocket` is not available in a bare AOSP build; use **`okhttp3.WebSocket`** if the app can vendor OkHttp, otherwise a ~150-line raw `SSLSocket`-free `Socket` implementation of RFC 6455 (the mux needs only: text frames out, text frames in, ping/pong reply, close). OkHttp is strongly recommended. `ws://` (not `wss://`) means no TLS setup.

```
connect  ws://127.0.0.1:3080/api/remote.mux
         headers: Cookie: dsh-auth-…=v1.…
                  (do NOT add Origin)
```

On open, for the conversation you want to watch:
```json
{"type":"open","streamId":"<uuid>","endpoint":"session/follow",
 "payload":{"args":{"request":{"address":{"kind":"session","sessionId":"<id>"},"assistantStream":true}}}}
```
Then send prompts over HTTP as usual. On each incoming text frame:

```java
JSONObject f = new JSONObject(text);
String sid = f.optString("streamId");
switch (f.optString("type")) {
  case "item": {
    JSONObject v = f.optJSONObject("value");
    if ("assistant-stream".equals(v.optString("type"))) {
      JSONObject inner = v.getJSONObject("frame");
      if ("chunk".equals(inner.optString("type"))) {
        JSONObject chunk = inner.optJSONObject("chunk");
        if (chunk != null && "text-delta".equals(chunk.optString("type"))) {
          appendToBubble(chunk.optString("text"));       // ← token-by-token
        }
      } else if ("end".equals(inner.optString("type"))) {
        finalizeTurn(inner.optJSONObject("outcome"));     // turn finished
      }
    } else if ("event".equals(v.optString("type"))) {
      JSONObject ev = v.getJSONObject("event");
      if ("assistant/message".equals(ev.optString("type"))) {
        replaceBubbleWithAuthoritative(extractText(ev));  // content[] where type=="text"
      }
    }
    break;
  }
  case "end":   /* logical stream closed; reopen on the next turn */ break;
  case "error": JSONObject err = f.getJSONObject("error"); /* code, message, details */ break;
}
```
`extractText` walks `event.data.message.content[]` and concatenates blocks whose `type` is `"text"` — identical to the existing `DshHttpTransport.textOf`, which is already correct.

**Reconnect:** if the socket drops, reopen it and re-send the same `open` frame with a **fresh** `streamId`; the `snapshot` frame replays the current window, so no history is lost. Re-auth on `401`/`403` at upgrade time.

**Threading:** one writer thread for the socket, one reader; deliver deltas to the UI with a `Handler` on the main looper. Debounce UI updates to ~50 ms rather than per token.

### Capability mapping for the app

| Capability | Implementation |
|---|---|
| (1) find or create a session | `session/list` → pick by `projections.values.title` / `updatedAt`, or `session/create` |
| (2) send a prompt | `session/prompt` (`mode:"queue"`) |
| (3) receive reply | `session/follow` + `assistantStream:true` over the mux; render `text-delta` |
| (4) audio → text | `speech/transcribe` with `audioBase64` from a 16 kHz mono PCM16 WAV recorder; `speech/catalog` for limits |
| (5) session list / latest | `session/list`; live updates via `session/control` (`payload:{"args":{}}`) |

**Cost note for on-device design:** creating a session and prompting it starts the real agent with its real system prompt and tool set. Reusing the user's existing session via `session/list` is far cheaper and is what "target the current conversation" should mean.

---

## 4. Transport B — `dsh sdk` / `dsh acp` stdio profiles

### 4.1 Shipped profile templates

`…/@deepseek-ai/dsh-app-boot/lib/index.js:529`:
```js
const PROFILE_TEMPLATES = {
	acp: { bundles: ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-acp-app"] },
	web: { bundles: ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"] },
	headless: { bundles: ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-headless"] },
	sdk: { bundles: ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-sdk-app"] },
	"sdk-minimal": { bundles: ["@deepseek-ai/dsh-sdk-minimal"] }
};
/** Installation-owned bundle tuples normalized to the shipped template. */
const INSTALLATION_OWNED_PROFILE_TUPLES = { headless: [ … ] };
/** The bundle list a `dsh plugin` init uses for a name with no shipped template. */
const DEFAULT_PROFILE_BUNDLES = ["@deepseek-ai/dsh-base"];
```

### 4.2 How to launch one

`…/@deepseek-ai/dsh/lib/bin.js:105` (commander declaration):
```js
.option("--profile <name>", "the profile under $DSH_HOME/profiles to boot", selectProfile)
.option("--from-default-profile <name>", "initialize a new custom profile from a shipped profile template")
.option("--patch <path>", "extra patch-list overlay applied after the profile layer (repeatable)", collect)
```
and `bin.js:22`: *"`dsh <name>` abbreviates `dsh --profile <name>`"*. So:

```bash
dsh sdk        # ≡ dsh --profile sdk   → @deepseek-ai/dsh-base + @deepseek-ai/dsh-sdk-app
dsh acp        # ≡ dsh --profile acp   → @deepseek-ai/dsh-base + @deepseek-ai/dsh-acp-app
# custom dir: dsh --profile mysdk --from-default-profile sdk
```
On first use the profile directory `$DSH_HOME/profiles/<name>` is initialized from the template and its bundles are installed with pnpm. **I deliberately did not run either command**: doing so would create a new directory under `~/.dsh/profiles` (explicitly off-limits for this task) and trigger a package install. Everything about Transport B below is therefore from code reading, not live execution — see UNCERTAINTIES.

### 4.3 SDK stdio JSON-RPC — the method set is tiny

`…/@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js:195`:
```js
	async handleRequest(method, params) {
		switch (method) {
			case "initialize": return this.initialize(params);
			case "session/prompt": return this.prompt(params);
			case "shutdown": return this.shutdown();
			default: throw new Error(`unknown DeepSeek Harness SDK runtime method: ${method}`);
		}
	}
```
**Three methods. No `session/list`, no `session/create`.** A session id is supplied by the client and materialized on first use (`getOrCreateSession` → `createSession` → `ctx.agents.create({sessionId, meta:{cwd}, agentOptions:{provider, model, …}})`, `:203-231`). `initialize` requires `{cwd, provider, model}` and optionally `reasoningEffort`, `maxTokens`; it also *mounts the DeepSeek adapter* if the provider is unowned (`:117-126`).

**Framing** — `…/@deepseek-ai/dsh-sdk-protocol/lib/index.js:5`:
> *Newline-delimited JSON-RPC 2.0 over byte streams. Frames with `id` and `method` are requests, `id` alone is a response, and `method` alone is a notification. Malformed lines are ignored; handler failures become error frames.*

No `Content-Length` headers. Requests carry `{"jsonrpc":"2.0","id":"req_<uuid-no-dashes>","method":…,"params":…}` (`:88-95`); notifications are `{"jsonrpc":"2.0","method":…,"params":…}` (`:131`).

**Notifications the server emits** (`constructor`, `:68-103`): `session.event` (`{sessionId, event}`), `session.status` (`{sessionId, status}`), `subagent.started`, `subagent.finished`.

**Streaming: no.** The forwarding source is `ctx.on("session/event", …)` — DSH **durable** session events. `assistant/message` is written once per step, when the step is assembled (`…/dsh-session/lib/types/types.d.ts:329`: *"Assembled assistant message for one step"*). There is no `assistant-stream` / `text-delta` forwarding anywhere in the SDK server, unlike `session/follow` with `assistantStream:true`. **The client would receive the whole assistant message in one notification, after the step completes.**

**Speech: none.** `grep -l 'transcrib\|speech'` over `dsh-sdk-app/lib/*.js`, `dsh-sdk-jsonrpc-server/lib/*.js`, `dsh-acp/lib/*.js`, `dsh-acp-app/lib/*.js` returns **no files**.

### 4.4 ACP — richer, still no speech, still not token-level

`…/@deepseek-ai/dsh-acp/README.md:64-76` documents the surface: `initialize`, `authenticate` ("the server requires no authentication"), `session/new`, `session/list`, `session/resume`, `session/close`, `session/set_config_option`, `session/prompt`, `session/cancel`, `session/update`, `session/request_permission`.

`session/list` here **does** read persisted sessions (newest-first pages, optional absolute `cwd` filter) — unlike the SDK. But `session/update` is explicitly described as *"**Committed** assistant messages and thoughts"*, and the implementation confirms it, `…/@deepseek-ai/dsh-acp/lib/index.js:554`:
```js
/** Standard ACP updates derived from committed DSH session events. */
async function assistantUpdates(ctx, session, event) {
	for (const block of event.data.message.content) {
		if (block.type === "reasoning") { … updates.push({ sessionUpdate: "agent_thought_chunk", … }); continue; }
		const content = await assistantBlockToAcp(ctx, block);
		if (content !== void 0) updates.push({ sessionUpdate: "agent_message_chunk", messageId: event.data.message.id, content });
	}
```
and it is called from exactly one place, `…/@deepseek-ai/dsh-acp/lib/index.js:880` (the builder is at `:562`):
```js
			if (event.type === "assistant/message") {
```
So `agent_message_chunk` is emitted **once per committed content block**, not per token. `grep 'text-delta'` over `dsh-acp/lib/index.js` → no hits. Also note the acp profile pins `model: deepseek-v4-flash` in `…/dsh-acp-app/cordis.patch.yml`, overriding the user's profile choice.

### 4.5 Does a second process drive the user's running instance? **No.**

This is the decisive architectural point and it is unambiguous:

- **Separate process.** `dsh sdk` is a new `node` process. Nothing in the SDK or ACP packages connects to `127.0.0.1:3080` or any other running instance. There is no IPC, no socket, no shared registry. `@deepseek-ai/dsh-sdk-jsonrpc-server` talks only over the new process's own stdin/stdout (`…/dsh-sdk-jsonrpc-server/lib/index.js:261-268`).
- **Separate in-memory runtime.** The SDK server keeps its own `sessions = new Map()` and creates its own agents. `hasLiveClient()`, running turns, tool approvals, and the GUI you see in the browser are all in the *web* process and are invisible to it.
- **The same files on disk, but that is not the same instance.** `@deepseek-ai/dsh-base` (present in both templates) mounts session persistence, which resolves its root through the harness home (`…/dsh-home-paths/lib/index.js:73`):
  ```js
  function resolveDshHome(configured, env = process.env) {
  	const fromEnv = env[DSH_HOME_ENV];
  	return resolve(expandHomePath(configured ?? (fromEnv !== void 0 && fromEnv.trim().length > 0 ? fromEnv : defaultDshHome())));
  }
  ```
  With `DSH_HOME` unset, that is `~/.dsh` for both processes, so both write `~/.dsh/sessions/<project>/<sessionId>/session.v4.jsonl.zstd` and both consult `~/.dsh/storages`. On this device:
  ```
  /data/data/com.termux/files/home/.dsh/sessions/--data-data-com.termux-files-home-oppo-watch-ai--/session-…/session.v4.jsonl.zstd
  /data/data/com.termux/files/home/.dsh/sessions/--data-data-com.termux-files-home-oppo-watch-ai--/session-…/session.lock
  ```
- **Concurrency guard is only a materialization lock.** `…/dsh-session-persistence-jsonl/lib/index.js:643` defines `LEASE_FILENAME = "session.lock"`, and `SessionWriteLease.acquire` (`:666`) takes a non-blocking `flock(2)` — the module doc at `:616` says *"POSIX takes a non-blocking `flock(2)` via native system support on `session.lock`"*. A second process cannot open a session that the first currently holds, but **the lock is per materialization, not per session lifetime**: I verified that the lock on an idle session created by the web server is free (`flock -n … -c 'echo GOT-LOCK'` → `GOT-LOCK`). So two runtimes can and will write the same event log at different times, with **no cross-process coordination of ordering or agent state** — the web server's cached projection would silently go stale. That is a data-integrity hazard, not a feature.

**Bottom line for Transport B:** even ignoring the missing speech-to-text and the missing token-level streaming, a `dsh sdk`/`dsh acp` process **cannot drive the conversation the user is having** — at best it appends to the same files behind the running instance's back. It is the right tool for *embedding a fresh, independent agent in another program*; it is the wrong tool for being a frontend of the running instance.

### 4.6 What it would take from Java (for completeness)

If the app nevertheless wanted Transport B: spawn `argv = ["/data/data/com.termux/files/usr/bin/node", "/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js", "--profile", "sdk"]` via `ProcessBuilder` with env `PREFIX=/data/data/com.termux/files/usr`, `HOME=/data/data/com.termux/files/home`, `PATH=…/usr/bin:…`, keep the process alive for the whole app session, write newline-terminated JSON to its stdin and read newline-framed JSON from its stdout, and **never let anything else log to stdout** (`…/dsh-sdk-app/cordis.patch.yml`: *"Stdout belongs exclusively to JSON-RPC."*). Then `initialize` → `session/prompt` → listen for `session.event`. The exit path is `shutdown` (disposes the root runtime and exits 0) or closing stdin. This is all mechanically possible from root Java — it is simply the wrong transport for this goal.

---

## (e) UNCERTAINTIES and unverified claims

1. **The auth patch is device-local and I did not test the upstream 401 path.** `isAuthenticated` is patched to `return true` in the installed tree. I could not observe a real `401` without modifying DSH, so the upstream cookie *verification* is proven only by (a) reading `decodeCookie`/`isAuthenticated` and (b) byte-exact reproduction of the server's own cookie signature. I did **not** demonstrate a request being accepted *because of* a minted cookie, since the patched server accepts everything.
2. **Launch-token lifetime is inferred.** `processLaunchToken` stores the token in a `WeakMap` keyed by the root context, generated once per process (`…/dsh-client-connection/lib/index.js:244`). I assume it is stable for the process lifetime and regenerated on restart; I did not restart the server to confirm (explicitly out of scope).
3. **`cookieMaxAgeDays` default 30** is read from the config schema (`:804`) and matched by the `Max-Age=2592000` observed in the live `Set-Cookie`. The app's `expiresAt` must satisfy `expiresAt - issuedAt <= maxAgeMilliseconds`; if the deployment lowers `cookieMaxAgeDays`, a 30-day cookie could be rejected. Safer: mint with a short lifetime (e.g. 1 day).
4. **Transport B was not executed.** Launching `dsh sdk`/`dsh acp` would create `~/.dsh/profiles/{sdk,acp}` (off-limits) and run pnpm installs. All Transport B claims come from source: the template table, the `switch` in `handleRequest`, the ACP update builder, the `grep` results for speech, and the persistence/lease code. The "no token-level streaming" conclusion is from code paths, not from a captured stdio session.
5. **The exact `session/control` request needs no arguments** — confirmed live (21 items in 10 s). I did not exercise its reconnect/`generation` semantics.
6. **Multi-root session accounting is approximate.** Disk holds 21 session directories across three project keys while `session/list` returned 20 at one instant (21 after my probe created one). `session/list` is populated from the live registry plus persistence promotion, not a raw disk scan; I did not map the discrepancy to a specific session.
7. **`$events`** — the gateway's second internal stream endpoint (`REMOTE_EVENT_STREAM_ENDPOINT = "$events"`, `…/dsh-api-gateway/lib/index.js:14`) — exists and is reachable in principle, but its payload contract is application-selected Cordis events and I did not exercise it. `session/control` is the documented, purpose-built alternative and is what I verified.
8. **Speech result quality is untested on real speech.** The probe proves the transport, validation, dispatch, and result shape; it produced `text: ""` because the input was a synthetic tone. I did not record real Mandarin audio, so transcription accuracy and latency for a real utterance are unmeasured (the provider was loaded and answered in 92 ms for 1.5 s of audio).
9. **Concurrency safety of Transport B against the live process is inferred.** The lease is per materialization and is released when idle (verified with `flock`), and the two processes share no agent state, so divergence is expected — but I did not run a two-writer experiment to observe corruption.
10. **Android WebSocket library choice is a recommendation, not a verified build.** I did not compile or run the Java code; the Java snippets are derived from the existing `DshHttpTransport.java` plus the verified wire protocol.
