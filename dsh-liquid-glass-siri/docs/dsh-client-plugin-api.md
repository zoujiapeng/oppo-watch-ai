# Third-party DSH **client (browser) plugin** — specification

Target: `web` platform, package ships **prebuilt JS**, no build step at install time.
All claims below were read out of the installed package tree at
`/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/` (abbreviated
`$DSH` = `$DSH/node_modules/@deepseek-ai`) plus the live server on
`http://127.0.0.1:3080`. Version in this install: **dsh 0.2.0-rc.2**.

---

## 0. TL;DR — the six things that must be right

1. `package.json` → `exports["./client"]` must point at the browser bundle.
2. `package.json` → `dsh.client.platform` must be exactly `"web"`.
3. `package.json` → `dsh.bundle.patch` must point at a patch file that mounts a Loader row.
4. The package must be in the profile's `dependencies` **and** `dsh.profile.bundles`.
5. `lib/client.js` must be a **classic** script (no `import`/`export`) that calls
   `window.__ModuleLoader__.load({ id: "<exact package name>", factory })`.
6. The factory must return an **object** with `apply(ctx)`, and the plugin should
   declare `inject` (`exports.inject = ["slots"]`) if it touches services.

---

## 1. `window.__ModuleLoader__.load({ id, factory })`

### 1.1 Where it lives

* The page-global facade is installed by an inline script from the host:
  `$DSH/dsh-client-modules/lib/index.js:453` `bootInjections(graph)` emits
  `kind:"script", placement:"head"` whose text is
  ```
  window.__ModuleLoader__={ mode:"queue", pendingQueue,
    load(registration){ pendingQueue.push(registration) },
    create(options){ ... } }
  ```
  So `load()` **queues** registrations until the shell builds the module system
  (`mode:"queue"`), then switches to `mode:"live"` and registers directly
  (`$DSH/dsh-client-modules/lib/client.js`, `ClientModuleSystem` constructor:
  `target.mode = "live"; target.load = (registration) => this.register(registration);`).
* The implementation proper is `ClientModuleSystem` in
  `$DSH/dsh-client-modules/lib/client.js` (source of truth: its `.d.ts` twins under
  `$DSH/dsh-client-modules/lib/types/client/`).
* The shell constructs it from `window.__DSH_BOOT__` in the frontend bundle
  `$DSH/dsh-web-frontend/dist/assets/index-5SrrfWpU.js`:
  `this.modules = o.create({ boot: n.__DSH_BOOT__, staticModules: rM(), ... })`
  where `rM()` is the **seed table** (see §1.3).

### 1.2 What `id` must be

**The npm package name — verbatim.** `WebBootEntry.id` is documented as
“Entry name == package name” (`lib/types/client/manifest.d.ts:50`) and
`register()` keys the factory table by `stripClientSuffix(registration.id)`
(`client.js`, `register(registration)`). Consequences:

* `id: "dsh-web-mobile-fix"` for a package `"name": "dsh-web-mobile-fix"`.
* `id: "@scope/pkg"` for a scoped package — the **bare** package name, not a path.
* A trailing `/client` is tolerated (`stripClientSuffix`, `manifest.d.ts:160`).
* Registering under any other id means the row's `arrive()` never finds its
  factory and boot fails with
  `client-modules: could not load "<row id>": <url>: loaded without registering "<row id>" via __ModuleLoader__.load`.
* A second registration for one id throws
  `client-modules: duplicate factory registration for "<id>" (bundle executed twice without invalidate?)`.

Live proof (fetched from the running server, `GET /plugins/??dsh-web-mobile-fix/client.js&rev=c4057d727a10`):

```js
window.__ModuleLoader__.load({
  id: "dsh-web-mobile-fix",
  factory: (require) => { ... exports.apply = apply; return module.exports; }
});
```

### 1.3 What `require()` can resolve

`factory(require)` receives the module-table require built by `makeRequire`
(`client.js:698`). Resolution order, exactly:

```js
const require = (spec) => {
  edges.add(spec);
  if (this.seed.has(spec)) return this.seed.get(spec);          // 1. platform seed word
  const id = stripClientSuffix(spec);
  const record = this.loadCache.get(id);
  if (record !== void 0) return record.exports;                  // 2. already materialized
  if (this.factories.has(id)) return this.materialize(id).exports; // 3. registered factory
  throw new Error(`client-modules: require("${spec}") missed the module table — ...`);
};
require.async = async (spec) => { /* "./client.<name>.js" → package-local chunk; else import(spec) */ };
```

**The seed table** (platform singletons hard-wired into the shell bundle,
`rM()` in `dsh-web-frontend/dist/assets/index-5SrrfWpU.js`) is exactly:

```js
{
  "react", "react/jsx-runtime", "react-dom", "react-dom/client",
  "@deepseek-ai/cordis",
  "@deepseek-ai/dsh-client-store",
  "@deepseek-ai/dsh-client-ui-slots",
  "@deepseek-ai/dsh-client-ui-primitives",
  "@deepseek-ai/dsh-client-ui-dockkit"
}
```

* **No relative paths in `require`.** `require("./x")` is a miss; the only
  relative form is `require.async("./client.<name>.js")`, restricted by
  `/^client\.[A-Za-z0-9][A-Za-z0-9._-]*\.js$/` (`lib/index.js:168`).
* **Other DSH packages resolve only if their own graph row has arrived first.**
  `require("@deepseek-ai/dsh-client-ui-layout")` works when that row is in the
  boot graph and either `loadCache`/`factories` already holds it. To force that,
  list the package in `dsh.client.external` (or `dsh.client.inject`) — see §2.
  `arriveGraphRow` (`client.js`) arrives every `external` entry that names a
  graph row before materializing the consumer.
* Anything else throws loudly. There is **no bundler and no fallback**: whatever
  you `require` must be a seed word or a boot-graph row.

### 1.4 What the factory must export; how `apply(ctx)` is invoked

* The factory returns `module.exports` (`Record<string, unknown>`), memoized in
  `loadCache` (`materialize()` in `client.js`). The factory runs **once**, lazily,
  at first import — not when the `<script>` executes.
* Fold that object becomes a **cordis plugin object**. The shell creates a Loader
  entry with `options.name = <id>` and cordis' `RegistryService.plugin` does:
  `new Fiber(this.ctx, config, Inject.resolve(plugin.inject), runtime, ...)`
  (`dsh-web-frontend/dist/assets/index-5SrrfWpU.js`, byte ~29284).
  `isPlugin` is `e && typeof e == "object" && typeof e.apply == "function"` —
  **a bare function export is NOT accepted**.
* Metadata read off the exports object (`$DSH/cordis/lib/types/registry.d.ts:52-63`,
  `Plugin.Base`): `name` (fiber/log name; the literal string `"apply"` is treated
  as “no name”), `Config` (standard-schema validator for the row's `config`),
  `inject` (services required; the fiber stays PENDING until all are provided),
  `provide`, `intercept`.
* `apply(ctx, config)` is called by the fiber runner: `s.callback(this.ctx, this.config)`
  (`dsh-web-frontend/.../index-5SrrfWpU.js`, Fiber `_runner.execute`). It may
  return nothing, a disposer, a promise, or an iterable of disposers.
* `ctx` **is a cordis `Context`** (the browser instance of the vendored
  `@deepseek-ai/cordis ~4.0.4`), wrapped by cordis' own Proxy handler
  (`ReflectService.handler`).

### 1.5 `ctx` API on the client

| API | Semantics | Evidence |
| --- | --- | --- |
| `ctx.get(name, strict = true)` | Reads a service **without** the inject requirement; returns `undefined` when absent or (strict) when the providing fiber is not ACTIVE (state 2). Never throws. | `$DSH/cordis/lib/types/reflect.d.ts:14`; impl `get(e,n=!0){return $n(this.ctx,this._getImpl(e,n)?.value)}` in the frontend bundle |
| `ctx.<service>` | Guarded. Not in `inject` → `Error("cannot get property \"<name>\" without inject")`. In `inject` but provider inactive → `"cannot get required service \"<name>\" in inactive context"`. Reserved keys that bypass the trap: `prototype`, `then`, symbols, numeric strings, and anything starting with `_` (`const tf=["prototype","then"]`). | ReflectService Proxy `get` trap, frontend bundle |
| `ctx.inject(deps, cb)` | Shorthand for `ctx.plugin({ inject: deps, apply: cb })`; cookie unloads/re-runs the callback whenever a required service changes. `deps` = `string[]` or `{name: interceptCfg}`. Returns `Fiber & PromiseLike<Fiber>`. | `$DSH/cordis/lib/types/registry.d.ts:104-111` |
| `ctx.effect(execute, label = "anonymous")` | Registers a fiber-owned effect. `execute()` may return one disposer, an iterable of disposers, a `Promise<Disposer>`, or an `AsyncIterable<Disposer>`; anything else → `TypeError("Invalid effect")`. Returns a disposer (awaitable). Disposers run in **reverse** order on unload. Creating an effect on a disposed fiber → `cannot create effect on inactive context`. | `$DSH/cordis/lib/types/fiber.d.ts:43-51,157-159`; impl `effect(e,n="anonymous")` frontend bundle |
| `ctx.on(event, listener, opts)` | Registers a listener; returns the unsubscribe. `opts` may be `{prepend:true}` or a boolean. `ctx.once`, `ctx.emit`, `ctx.parallel`, `ctx.serial`, `ctx.bail`, `ctx.waterfall` also exist. | `events` mixin in the frontend bundle |
| `ctx.provide(name, value)` / `ctx.set` | Provide/patch a service owned by the current fiber. | `$DSH/cordis/lib/types/reflect.d.ts:29-43` |
| `ctx.plugin(p, cfg)` | Load a nested plugin in this context. | `registry.d.ts:120` |
| `ctx.fiber` | `state`, `effect`, `runtime`, `assertActive()`, `uid`. | `fiber.d.ts` |
| `ctx.logger` | `LoggerService`; `ctx.logger.info(...)` / `ctx.logger("name").info(...)`. Not gated by inject. | `$DSH/cordis/lib/types/context.d.ts:26-27` |

Built-in cordis events (`$DSH/cordis/lib/types/events.d.ts:216-239`):
`internal/plugin`, `internal/status`, `internal/config` (waterfall),
`internal/service`, `internal/update` (waterfall), `internal/get` (waterfall),
`internal/set` (waterfall), `internal/listener` (bail), `internal/dispatch`.

On top of those, DSH's own client plugins publish a small set of **named events
on the same `ctx.events` bus** (no separate bus service). Discovered emitters and
listeners in shipped client bundles:

| Event | Payload | Emitter | Example listeners |
| --- | --- | --- | --- |
| `locale/change` | locale snapshot | `dsh-client-locale/lib/client.js:1449` | `dsh-client-locale:1528`, `dsh-client-ui-input-trigger:893` |
| `theme/change` | theme snapshot | `dsh-client-ui-theme/lib/client.js:1518` | `dsh-client-ui-layout:677`, `dsh-client-ui-theme:1595` |
| `slots/changed` | slot key | `dsh-client-ui-renderer/lib/client.js:1325` (SlotRegistry mutation bridge) | internal |
| `connection/reset` | — | `dsh-api-gateway/lib/client.js:1608` | 12+ packages (`ui-commands:806`, `ui-settings:1514`, `ui-cordis:1407`, `cordis-client-runner:6595`, …) |
| `command/executed` | `(sessionId, commandName, result)` | `dsh-client-ui-commands:1085` | `dsh-session-log-export:288` |
| `slash/input-begin-command`, `slash/input-insert-reference`, `slash/input-consume-token`, `slash/input-insert-text` | request objects | `dsh-client-ui-input-trigger:715` (dispatched with `actx.bail(...)`) | `dsh-client-ui-conversation:14263-14266` (scoped ctx) |

These are ordinary conventions, not a declared contract: a third-party plugin may
`ctx.on(...)`/`ctx.emit(...)` any string, but only the names above are produced by
shipped code, and only `locale/change`, `theme/change`, `connection/reset` are
useful to a UI plugin.

### 1.6 Module id → URL, and how the server serves it

Route registration — `$DSH/dsh-client-modules/lib/index.js:546-552`:

```js
webCtx.effect(() => webCtx.webServer.register({
  kind: "prefix", path: PLUGIN_ROUTE, handler: this.serveBundle
}), "client-modules: bundle route");
```
with `const PLUGIN_ROUTE = "/plugins"` (`index.js:201`).
Index injection — `index.js:553`: `ctx.on("webserver/index-inject", (table) => table.push(...bootInjections(this.composed)))`.

URL shapes (`index.js:203-222`):

```js
comboSearch(ids, rev) => `??${ids.map(id => `${id}/client.js`).join(",")}&rev=${rev}`
comboUrl(ids, rev)      => `/plugins/${comboSearch(ids, rev)}`
chunkUrl(id, fileName, rev) => `/plugins/${id}/${fileName}?rev=${rev}`
```

Browser-facing references are **document-relative** (`comboReference` strips the
leading slash), so what you actually see in `__DSH_BOOT__` and the network tab is:

```
plugins/??dsh-web-mobile-fix/client.js&rev=c4057d727a10        # one-plugin combo
plugins/??<id1>/client.js,<id2>/client.js&rev=<12hex>          # batch combo
plugins/<id>/client.<chunk>.js?rev=<12hex>                     # require.async chunk
plugins/<id>/client.js.map&rev=<12hex>                         # source map (optional file)
```

Verified live:

```
$ curl -D- 'http://127.0.0.1:3080/plugins/??dsh-web-mobile-fix/client.js&rev=c4057d727a10'
HTTP/1.1 200 OK
content-type: text/javascript; charset=utf-8
cache-control: public, max-age=31536000, immutable
... body = lib/client.js verbatim + "\n//# sourceMappingURL=??dsh-web-mobile-fix/client.js.map&rev=c4057d727a10\n"
$ curl -o /dev/null -w '%{http_code}' '...&rev=000000000000'
404
```

`rev` is `artifactRevision({mtimeMs, ctimeMs, size})` — sha1 of the three stat
fields, 12 hex chars (`index.js`, `artifactRevision`/`framedHash`). A stale or
guessed rev 404s; only the exact advertised URL is served.

The bundle is read **eagerly** at activation (`reconcilePackage` →
`initialBundleSnapshot`, `index.js:881`), so `lib/client.js` must exist when
`dsh` starts. Missing file →
`client-modules: client bundle not found; run \`pnpm run build\` before launch: package: X / path: Y`
aggregated into `ClientPackageCompositionError`, which is thrown from the
`ClientModuleRegistry` **service constructor** → the row is a FAILED fiber.

The `.map` file is optional: `readSourceMap` swallows `ENOENT` and substitutes an
identity line map.

---

## 2. The `dsh.client` field in `package.json`

Type: `DshClientManifest` — `$DSH/dsh-package-manifest/lib/types/types.d.ts:76-89`.
Validator: `parseDshClient` — `$DSH/dsh-client-modules/lib/index.js:61-73`
(duplicated in `lib/client.js:61`).

| Subfield | Type | Meaning | If wrong / absent |
| --- | --- | --- | --- |
| `platform` | `string` (**required**) | Client platform id; the Web consumer selects `"web"` (`index.js:714`). | Absent or non-object `dsh.client` → the package is **silently** treated as “not a client package” (`pkgMeta.set(sourceKey, null)`); the node half still mounts, the browser half never loads, **no error**. Wrong value (`"desktop"`, `"Web"`) → same silent skip. Non-string → `client-modules: <pkg> dsh.client.platform must be a string`. Non-object `dsh.client` → `client-modules: <pkg> has a non-object dsh.client declaration`. |
| `inject` | `string[]` (optional) | *Package-name* graph edges, **not** cordis service injection: “Informational package-name dependencies” (`types.d.ts:79`). At runtime `arriveGraphRow` arrives each named **graph row** (and its factories) **before** this row materializes. Also used by entry composition. | Absent = no edges. A name that is not a boot-graph row is simply skipped (no error). Must not name the package itself: `"<id>" requests module "<name>" that it answers itself — a row must not declare its own package in dsh.client.external`. Non-string array → `client-modules: <pkg> dsh.client.inject must be a string array`. |
| `external` | `string[]` (optional) | “Exact module-table requests beyond the implicit client baseline, including subpaths such as `<pkg>/client`” (`types.d.ts:83-88`). Only affects arrival ordering + `arriveGraphRow`; it does **not** pre-validate names. | Absent → baseline seeds only. A typo costs nothing at compose time but your `require("<typo>")` throws at materialization. |
| `immediately` | `boolean` (optional) | Stage-one prefetch: the shell `await`s `modules.prefetch(id)` for every `immediately` row during module-face boot, registering its factory before the application tier. | Absent/false → the row loads in the shared `application` batch. Non-boolean → `client-modules: <pkg> dsh.client.immediately must be a boolean`. |

Live example from the running graph (67 rows):

```json
{"id":"dsh-web-mobile-fix","url":"plugins/??dsh-web-mobile-fix/client.js&rev=c4057d727a10","rev":"c4057d727a10"}
{"id":"@deepseek-ai/dsh-experimental-client-ui-voice-input",
 "url":"plugins/??@deepseek-ai/dsh-experimental-client-ui-voice-input/client.js&rev=22bdde3862db",
 "rev":"22bdde3862db",
 "inject":["@deepseek-ai/dsh-api-remotes","@deepseek-ai/dsh-api-session-controller","@deepseek-ai/dsh-client-locale",
           "@deepseek-ai/dsh-client-ui-conversation","@deepseek-ai/dsh-client-ui-primitives","@deepseek-ai/dsh-api-gateway",
           "@deepseek-ai/dsh-client-ui-plugin-manager"],
 "external":["@deepseek-ai/dsh-api-gateway/client"]}
{"id":"@deepseek-ai/dsh-client-hmr", "...":"...", "inject":["@deepseek-ai/dsh-client-modules"], "immediately":true}
```

**`exports["./client"]`** — resolved by `clientExportOf` (`index.js:171-181`):

```js
const client = exportsField["./client"];
if (typeof client === "string") return client;                     // OK
if (typeof client === "object") if (typeof client.default === "string") return client.default; // OK
throw new Error(`client-modules: ${pkgName} exports["./client"] must be a string or an object with a string default`);
```
Declaring `dsh.client` without it throws
`client-modules: <pkg> declares dsh.client but exports no "./client" bundle`
(`index.js:719`) → loud boot failure.

**`dsh.bundle.patch`** is what actually mounts the row (see §3). A package with
`dsh.client` but no `dsh.bundle.patch` is never scanned, because the scan is
driven entirely by **Loader entries** (`ClientModuleRegistry` listens to
`internal/plugin` and seeds from `ctx.loader.entries()`), never by the profile's
dependency list.

**`dsh.compatibility`** (present in both reference plugins) is **not read** by
`evaluatePluginCompatibility` — that function only inspects `peerDependencies`
whose key is `@deepseek-ai/dsh` or starts with `@deepseek-ai/dsh-`
(`dsh-app-boot/lib/index.js:286-313`). Treat `dsh.compatibility` as decorative
metadata for the plugin manager UI.

> **Compatibility trap:** any `peerDependencies` entry on `@deepseek-ai/dsh-*`
> that does not satisfy the running runtime version makes the bundle
> **skipped** at boot with a stderr warning, not an error
> (`loadProfileDirectory` catch → `skippedBundles`). Omit such peers, or use `"*"`.

---

## 3. How a profile mounts a plugin

### 3.1 The profile directory

`~/.dsh/profiles/web/` (i.e. `$DSH_HOME/profiles/<name>`), initialised by
`initProfile` (`dsh-app-boot/lib/index.js:574-590`) with:

* `package.json` — `{ name: "dsh-profile-web", private: true, dependencies: {...},
  dsh: { profile: { bundles: [...] } } }`
* `cordis.patch.yml` — the user patch layer (`PROFILE_PATCH_FILENAME`, `index.js:487`)
* `pnpm-workspace.yaml` — exactly (`PROFILE_PNPM_WORKSPACE`, `index.js:563`):
  ```yaml
  packages:
    - .

  nodeLinker: hoisted
  autoInstallPeers: false
  ```

### 3.2 Patch-layer composition order

`readProfilePatches` (`dsh-app-boot/lib/index.js:1023`):

```js
const patches = [
  ...profile.layers.flatMap(layer => layer.patches),   // 1. every bundle's dsh.bundle.patch, in dsh.profile.bundles order
  ...profile.cordis.patch.yml                          // 2. the profile's own user layer
  ...$DSH_HOME/cordis.patch.yml                        // 3. the Harness-home layer
  ...context.overlays                                  // 4. --patch files, in order, then flag-derived patches
];
// then, last, the DSH_TELEMETRY_DISABLED-derived { id: "session-telemetry-otel", disabled: true }
```
All layers are flattened into **one** list and applied by a single
`applyEntryPatches` pass over an empty root (`composeEntries`,
`index.js:997`), so a later layer can patch a row an earlier layer inserted.
Module resolution is two-anchor: a bundle name resolves **first from the dsh
installation**, then from the profile directory (`resolveBundleDir`,
`index.js:901-907`).

### 3.3 Patch entry schema (verified, incl. live negative tests)

Base entry shape: `EntryOptions` (`$DSH/cordis-plugin-loader/lib/types/config/entry.d.ts:6-19`)
= `{ id, name, config?, group?, disabled?, inject? }` plus `intercept`, `isolate`.

`applyEntryPatches` (`dsh-app-boot/lib/index.js:61-113`) in full:

```js
function applyEntryPatches(data, patches, warn) {
  if (!patches?.length) return [...data];
  data = structuredClone(data);
  const entryMap = new Map();
  const buildMap = (entries) => { for (const entry of entries) {
      if (entry.id) entryMap.set(entry.id, entry);
      if (entry.group && Array.isArray(entry.config)) buildMap(entry.config); } };
  buildMap(data);

  for (const patch of patches) {
    const { id, insert, name, ...overrides } = patch;

    if (insert) {
      if (id) {                                    // insert INTO the group named by id
        const target = entryMap.get(id);
        if (!target) { warn("patch insert: entry %C not found", id); continue; }
        if (!target.group) { warn("patch insert: entry %C is not a group", id); continue; }
        if (!Array.isArray(target.config)) target.config = [];
        target.config.push(...insert);
      } else data.push(...insert);                 // no id → APPEND AT TOP LEVEL
      buildMap(insert);                            // inserted rows become patchable
      continue;
    }

    if (!id) { warn("patch: id is required for non-insert patches"); continue; }
    const target = entryMap.get(id);
    if (!target) { warn("patch: entry %C not found", id); continue; }
    if (name && name !== target.name) {            // `name` ASSERTS, it never renames
      warn("patch: name mismatch for %C (expected %C, got %C), skipping", id, target.name, name);
      continue;
    }
    for (const [key, value] of Object.entries(overrides)) {
      if (key === "id") continue;
      target[key] = value;                         // WHOLESALE field replacement
    }
  }
  return data;
}
```

| Key | Semantics |
| --- | --- |
| `insert` | Array of complete entries. With **no** `id` on the patch → appended to the **top level** of the composed list. With `id` → appended to that row's `config` array, and the row **must** have `group: true`. |
| `id` | Targets a row by its Loader entry id, indexing nested groups too (`group: true` + `config: [...]`). Unknown id → warning, patch skipped, boot continues. |
| `name` | Module specifier on an inserted row. On a **non-insert** patch it is an *assertion*: if truthy and it differs from the target's current `name`, the patch is skipped with a warning. It never renames. |
| `config` | Replaced **wholesale**, not deep-merged (`target[key] = value`). The shipped `dsh-web-app/cordis.patch.yml` states this explicitly: “A patch replaces the targeted row's whole `config`, so each row below restates every key it owns.” |
| `disabled` | Boolean or `!!js` expression; evaluated against the loader context (`Entry.disableOf`: “a `!!js` expression evaluates against the loader context”). Truthy coerces. |
| `group` | `true` marks a row as a nested entry group whose `config` is an entry list. |
| `inject`, `intercept`, `isolate` | Passed through verbatim onto the target row. |
| `!!js` | Supported by the YAML dialect for entry-list configs and patches (`userPatchesSchema`, `parsePatchList`). `!!js "expr"` is stored verbatim, evaluated later against the loader ctx, e.g. `disabled: !!js "ctx.get('profileContext')?.name !== 'desktop'"`. |
| ordering | The YAML array order is the application order; composition is a single flattened pass, so a patch may target a row an earlier patch inserted. Ties in row order are decided by insertion position. |

**Live negative tests** (`dsh --profile web --patch <file> --dump-config`):

```
- id: no-such-entry + config            → dsh: [f] patch: entry "no-such-entry" not found          (exit 0, warns)
- insert: [...] + id: no-such-group     → dsh: [f] patch insert: entry "no-such-group" not found    (exit 0, warns)
- config: {...}  (no id, no insert)     → dsh: [f] patch: id is required for non-insert patches     (exit 0, warns)
- id: client-hmr + name: 'wrong-name'   → dsh: [f] patch: name mismatch for "client-hmr"
                                          (expected "@deepseek-ai/dsh-client-hmr", got "wrong-name"), skipping
- file is not a YAML array              → Error: dsh: overlay <f> must be a top-level YAML array of loader patch entries  (exit 1, throws)
- insert: 'nope' (non-array)            → TypeError: patch.insert?.forEach is not a function         (exit 1, throws, at anchorInsertedPluginNames)
```

Relative/absolute `name` values inside an `insert` list are converted to
`file:` URLs anchored beside the patch file (`anchorInsertedPluginNames`,
`index.js:3540`).

### 3.4 `dsh.profile.bundles`

Read at `dsh-app-boot/lib/index.js:921`:
`const bundles = readProfileManifest(binName, dir).dsh?.profile?.bundles ?? [];`
then, per name, in order (`loadProfileDirectory`, `index.js:920-953`):

1. `resolveBundleDir` — installation anchor first, then the profile dir;
   unresolvable → thrown → the bundle is **skipped** with
   `cannot resolve profile bundle "<name>" from the dsh installation or <dir>; run 'dsh plugin --profile <name> install' if its dependency is not installed`.
2. `bundleManifest.dsh.bundle` must exist, else
   `profile bundle "<name>" declares no dsh.bundle in its package.json` → **skipped**.
3. `evaluatePluginCompatibility` — incompatible `@deepseek-ai/dsh*` peers → **skipped**.
4. `bundlePatchPaths(packageDir, bundle)` → `loadOverlayPatches` per file
   (a **missing** declared patch file **throws**, `loadOverlayPatches`).
   `dsh.bundle.patch` may be a string or an ordered string array.

Skipped bundles print once per start:
`dsh: skipping profile bundle "<name>": <reason>` (`reportSkippedBundles`, `index.js:516`).

**A package must be in BOTH `dependencies` and `dsh.profile.bundles`.**
`dependencies` alone installs the code and nothing else happens.
`bundles` alone cannot resolve. `dsh plugin --profile web install <spec>`
automatically appends any newly added dependency that declares `dsh.bundle`
to `bundles` (`dsh-plugin-manager/lib/index.js`, `reconcile`, ~line 1112):
`for (const dependency of after.dependencies) if (dependency.bundle && !disabled.has(...) && !bundles.includes(...)) bundles.push(dependency.name);`

`cordis.yml` in the profile directory is **not** the composition input — the
profile's tree is composed from the bundle patches + `cordis.patch.yml`.
(`--dump-config` prints the composed result; it is a diagnostic view, not a
committed artifact.)

### 3.5 `link:` dependencies with pnpm

* The reference profile already uses it successfully:
  `"dsh-preset-mobile-use": "link:/data/data/com.termux/files/home/dsh-preset-mobile-use"`
  → `node_modules/dsh-preset-mobile-use -> ../../../../dsh-preset-mobile-use`,
  recorded in `pnpm-lock.yaml` as `specifier: link:/... ; version: link:../../../dsh-preset-mobile-use`.
* `link:` creates a **symlink**, so the target directory is the live source —
  edit `lib/client.js` there and the served bytes change. `file:` would copy.
* Because the browser bundle is prebuilt JS, the linked directory needs **no
  `node_modules` and no build step**; nothing is compiled at install time.
  (`dsh-web-mobile-fix` ships exactly `lib/client.js` + `lib/index.js` + `package.json` + `cordis.patch.yml`.)
* `pnpm-workspace.yaml` uses `nodeLinker: hoisted` and `autoInstallPeers: false`;
  it does not change `link:` semantics — it only keeps the profile's tree flat.
* `dsh plugin --profile web install /abs/path` anchors a relative path spec to
  the caller's cwd (`anchorPathSpec`, `dsh-plugin-manager/lib/index.js:215`) and
  runs `pnpm` **inside the profile directory** — it writes `package.json` and
  `pnpm-lock.yaml` for you. Manual `pnpm add` in the profile dir works too.
* Symlinked packages resolve correctly for the client scan: `locatePkgJson`
  resolves the row specifier through Node's own ESM resolution and then walks up
  to the nearest `package.json` whose `name` matches
  (`dsh-client-modules/lib/index.js:752-800`), so a realpath outside the profile
  tree is fine. Verified in practice by `dsh-preset-mobile-use`.

---

## 4. The client UI slot system

### 4.1 Where it lives

* `$DSH/dsh-client-ui-slots` — the pure core `SlotCore` + all the TypeScript
  contracts (`lib/types/index.d.ts`, `renderer.d.ts`, `store.d.ts`).
* `$DSH/dsh-client-ui-renderer` — the cordis **service** `slots: SlotRegistry`
  (extends `SlotCore`, adds `ctx.effect`-based disposal, `inject()`, the React
  renderer install, store instances) plus the actual React outlets.
* `$DSH/dsh-client-ui-layout` — declares the top-level seats.

### 4.2 The API — `ctx.slots`

`SlotRegistry` (`$DSH/dsh-client-ui-renderer/lib/types/client/registry.d.ts:85-190`):

| Member | Signature | Notes |
| --- | --- | --- |
| `ctx.slots.register(options, Component)` | `(options, component) => () => void` | Contribute a component into a declared slot; optionally declare child slots, a store seat, an inject face, a locale namespace. Returns an idempotent disposer. Declared as a **prototype method** on purpose: “the cordis service proxy binds `this.ctx` to the CALLER's context at call time, which is what routes the effect (and the unload cascade) into the caller's fiber.” So disposal is automatic on fiber unload — **you do not need to wrap it in `ctx.effect` yourself.** |
| `ctx.slots.inject(key, cb)` | `(key, () => Disposer \| Iterable<Disposer>) => () => void` | Run `cb` when slot `key` is **declared**; immediately if already declared, again after each re-declaration. Callback effects are owned by the caller's fiber. This is the correct way to survive plugin activation order. |
| `ctx.slots.registerFactory(options, Component)` | `RegisterFactory` | Reusable component factory (advanced). |
| `ctx.slots.entries(key)` / `entriesOfSlot(key)` / `spec(key)` / `subscribe(key, fn)` / `getVersion(key)` | inspection | `entriesOfSlot` = shadowing winners. |
| `ctx.slots.provideRoot(contribution)` | domain root data | e.g. ui-layout contributes `{ hooks: { panelInfo } }`. |
| `ctx.slots.install(renderer)` / `installLocale(face)` | boot-once | owned by the shell / locale plugin. |

Options (`BaseOptions` + `KindOptions`, `ui-slots/lib/types/index.d.ts:560-613`):

```ts
{ name: <slot key>,                 // required
  // kind 'single' : { priority? }
  // kind 'keyed'  : { key: string, priority? }
  // kind 'list'   : { id: string, order?: number, label?: string|()=>string, priority? }
  // kind 'chain'  : { select: (owner) => M|null, priority? }
  children?: { [childSlotKey]: { kind, scope, inject? } },  // DECLARE child slots you will render
  store?, inject?, locale?, registrant? }
```

Load-time validation throws with these exact messages (frontend bundle,
`SlotCore.register`):

```
slot "<name>" is not declared (a parent entry's children table must declare it)
single slot "<name>" already has a registration at priority N (registered by X) — register at a different priority to shadow it (lowest renders)
list  slot "<name>" requires options.id
keyed slot "<name>" requires options.key
list  slot "<name>" already has an entry with id "<id>" at priority N ...
```
Shadowing: entries sharing one cell (single = the slot; keyed = same `key`;
list = same `id`) coexist at distinct `priority`, ascending, ties keep
registration order; **the lowest live entry renders**.

### 4.3 Slot names

Every outlet renders `<div data-slot="<slotKey>" style="display:contents">`
(`dsh-client-ui-renderer/lib/client.js:1100`, root at `:1218`), so you can
confirm any key from DevTools.

Full `SlotMap` (kind / scope / declaring package), extracted from the `.d.ts`
declaration merges:

```
conversation.approval.detail                single  session         ui-approval
conversation.chat.assistant-actions         list    session         ui-chat
conversation.chat.commandview               keyed   session         ui-chat
conversation.chat.node                      keyed   session         ui-chat
conversation.chat.turnTail                  list    session         ui-chat
conversation.composer                       chain   session         ui-conversation
conversation.composer.bar                   single  session-maybe   ui-conversation
conversation.composer.dock                  list    session         ui-conversation
conversation.content                        single  session-maybe   ui-conversation
conversation.header                         single  session-maybe   ui-conversation
conversation.header.leading                 single  root            ui-conversation
conversation.hero.agentPreset               single  session-maybe   ui-conversation
conversation.hero.brand.mark                single  root            ui-conversation
conversation.hero.workspace                 single  root            ui-conversation
conversation.hero.workspace.directoryFlow   single  root            ui-workspace
conversation.input.activity                 single  session         ui-conversation
conversation.input.attachments              single  session-maybe   ui-conversation
conversation.input.dock                     list    session         ui-conversation
conversation.input.left                     list    session         ui-conversation
conversation.input.model                    single  session         ui-conversation
conversation.input.overlay                  list    session         ui-conversation
conversation.input.permission               single  session         ui-conversation
conversation.input.plan                     single  session         ui-conversation
conversation.input.right                    list    session         ui-conversation
conversation.message.images                 single  session         ui-chat
conversation.plan-review.actions            list    session         ui-user-questions
conversation.session                        single  session         ui-conversation
conversation.session.header                 single  session         ui-conversation
conversation.session.header.actions         list    session         ui-conversation
conversation.session.header.corner          single  session         ui-conversation
conversation.session.header.lineage         single  session         ui-conversation
conversation.session.header.utilities       list    session         ui-conversation
conversation.trajectory.images              single  session         ui-trajectory
conversation.view                           list    session         ui-conversation
deliverables.file.actions                   list    session         ui-deliverables
deliverables.review.file.actions            list    session         ui-deliverables
main                                        keyed   root            ui-layout      (reserved key "conversation")
main.conversation                           single  session-maybe   ui-conversation
plugins.bundle.activation                   keyed   root            ui-plugin-manager
plugins.bundle.config                       keyed   root            ui-plugin-manager
plugins.detail.actions                      list    root            ui-plugin-manager
plugins.detail.badge                        list    root            ui-plugin-manager
plugins.detail.section                      list    root            ui-plugin-manager
plugins.item                                list    root            ui-plugin-manager
plugins.row.config                          keyed   root            ui-plugin-manager
rightbar                                    single  root            ui-layout
rightbar.session                            single  session         ui-sidebar-right
root                                        single  root            ui-renderer    (DO NOT REGISTER)
settings.action                             list    root            ui-settings
settings.close                              single  root            ui-settings
settings.general.item                       list    root            ui-settings
settings.header                             single  root            ui-settings
settings.launcher                           single  root            ui-settings
settings.models.footer                      list    root            ui-settings-models
settings.models.provider-card               keyed   root            ui-settings-models
settings.models.sign-in                     single  root            ui-settings-models
settings.onboarding                         list    root            ui-settings
settings.plugins.tab                        list    root            ui-settings
settings.section                            list    root            ui-settings
settings.trigger                            single  root            ui-settings
shell.leading                               single  root            ui-layout
shell.overlay                               list    root            ui-layout      ← THE FRAME-WIDE OVERLAY
shell.quota-notice                          chain   root            ui-chat
sidebar                                     single  root            ui-layout
sidebar.brand.mark                          single  root            ui-sidebar
sidebar.brand.name                          single  root            ui-sidebar
sidebar.chat.conversation                   single  session         ui-subagent
sidebar.footer.action                       list    root            ui-sidebar
sidebar.panellist                           list    root            ui-sidebar
sidebar.right.pane.tab                      keyed   session         ui-sidebar-right
sidebar.right.pane.tab.title                keyed   session         ui-sidebar-right
sidebar.right.tab.document                  keyed   session         ui-sidebar-documentpreview
sidebar.right.tab.document.action           keyed   session         ui-sidebar-documentpreview
sidebar.right.tab.document.actions          list    session         ui-sidebar-documentpreview
sidebar.right.tab.document.office.pdf       keyed   session         ui-sidebar-documentpreview
sidebar.right.tab.document.unpreviewable    list    session         ui-sidebar-documentpreview
sidebar.right.tab.files.actions             list    session         ui-sidebar-files
sidebar.right.tab.guide                     chain   session         ui-sidebar-right
sidebar.right.tab.guide.entry               keyed   session         ui-sidebar-right
sidebar.right.tab.menu.item                 list    session         ui-sidebar-right
sidebar.session.row.hover                   list    root            ui-workspace
sidebar.session.row.leading                 list    root            ui-workspace
sidebar.settings                            single  root            ui-sidebar
sidebar.toggle.badge                        single  root            ui-sidebar
sidebar.workspaces                          single  root            ui-sidebar
sidebar.workspaces.directoryFlow            single  root            ui-workspace
sidebar.workspaces.session.menu.item        list    root            ui-workspace
sidebar.workspaces.session.row.action       list    root            ui-workspace
tool.call.images                            single  session         ui-tool
tool.call.toolview                          keyed   session         ui-tool
tool.view.cordis                            keyed   session         ui-cordis
```

### 4.4 Is there a ROOT / full-screen overlay slot? — **Yes: `shell.overlay`**

`ui-layout/lib/types/client/index.d.ts` documents it verbatim:

> “Frame-wide floating layer, above every column and outside their scroll
> containers. Deliberately generic and unowned by any feature: a badge, a toast
> stack or a status pill all belong here, and entries order among themselves.
> The layer itself is click-through — entries opt back into pointer events — so
> an occupant never blocks the app underneath. This is the additive seat for a
> frame-wide surface of your own: a fresh `id` is added beside the shipped
> entries instead of replacing them.”

`root` is `single`/`root` and **explicitly occupied** by ui-layout's `AppFrame`.
Registering there shadows the whole app:

> “DO NOT register here. … a dynamically registered entry is assigned a lower
> priority than the shipped one, which makes it the winner: the page would render
> your component alone, with every seat the frame declares gone. For a surface of
> your own that floats over the whole app, register into `shell.overlay` instead.”
> (`ui-renderer/lib/types/client/registry.d.ts:17-36`)

The layer's real markup and CSS (`ui-layout/lib/client.js:344-346` and the CSS
module string at `:73`):

```jsx
<div className={css.overlayLayer} data-shell-overlay={true}>{overlays}</div>
```
```css
.pI_x6G_overlayLayer { position:absolute; inset:0; z-index:20; pointer-events:none }
.pI_x6G_overlayLayer > * { pointer-events:auto }
```

The frame is `position:relative; overflow:hidden; height:100%`. Because each slot
outlet is a `display:contents` div, **your rendered component becomes a direct
child of the overlay layer** and therefore automatically gets
`pointer-events:auto`. Use `position:fixed` inside it if you need true viewport
coverage.

### 4.5 Registration code example (adapted from real code)

Real declaration site — `ui-layout/lib/client.js:601`:

```js
const disposeRegistration = ctx.slots.register({
  name: "root",
  locale: "common",
  children: {
    "sidebar":      { kind: "single", scope: "root" },
    "main":         { kind: "keyed",  scope: "root" },
    "rightbar":     { kind: "single", scope: "root" },
    "shell.overlay":{ kind: "list",   scope: "root" },
    "shell.leading":{ kind: "single", scope: "root" },
  },
  store,
}, AppFrame);
```

Real consumption pattern — `ui-brand-official/lib/client.js:36-38`:

```js
const inject = ["slots"];
function apply(ctx) {
  ctx.slots.inject("sidebar.brand.mark", () => ctx.slots.inject("sidebar.brand.name", function* () {
    yield ctx.slots.register({ name: "sidebar.brand.mark" }, OfficialBrandMark);
    yield ctx.slots.register({ name: "sidebar.brand.name" }, OfficialBrandName);
  }));
}
exports.apply = apply;
exports.inject = inject;
```

Minimal third-party overlay (this repo's skeleton, `lib/client.js`):

```js
window.__ModuleLoader__.load({
  id: "dsh-client-plugin-skeleton",
  factory: (require) => {
    var module = { exports: {} }; var exports = module.exports;
    var React = require("react");               // seed word, no build step
    var h = React.createElement;

    function Overlay() {
      var s = React.useState(false), open = s[0], setOpen = s[1];
      return open
        ? h("div", { style: { position:"fixed", inset:0, zIndex:50, background:"rgba(8,10,14,.72)" } },
            h("button", { onClick: () => setOpen(false) }, "Close"))
        : h("button", { style:{ position:"fixed", right:18, bottom:18, zIndex:30 },
                        onClick: () => setOpen(true) }, "Open");
    }

    function apply(ctx) {
      var style = document.createElement("style");
      style.setAttribute("data-plugin", "dsh-client-plugin-skeleton");
      style.textContent = ".my-css{color:red}";
      document.head.append(style);
      ctx.effect(() => () => style.remove(), "skeleton: stylesheet");

      ctx.slots.inject("shell.overlay", () =>
        ctx.slots.register({ name: "shell.overlay", id: "skeleton-overlay", order: 50 }, Overlay));
    }

    exports.apply = apply;
    exports.inject = ["slots"];                 // cordis service inject, NOT dsh.client.inject
    return module.exports;
  },
});
```

The component receives the composed slot props. For `shell.overlay`
(`list`/`root`, no `owner` declared) that is `renderSlot`,
`renderFactorySlot` plus the merged `GlobalStandardProps` — you may ignore them.
Session-scoped slots additionally receive `sessionId` and the session standard
hooks (`ui-slots/lib/types/index.d.ts:191-217`, `renderer.d.ts`).

---

## 5. Can a client plugin be pure vanilla JS (no React)? — **Yes**

`dsh-web-mobile-fix` is the existence proof: its entire browser half
(`~/.dsh/profiles/web/node_modules/dsh-web-mobile-fix/lib/client.js`, served
verbatim by the live server) is a classic script that

* builds a CSS string and appends `<style data-plugin="dsh-web-mobile-fix">`,
* adds a capture-phase `document.addEventListener("click", ..., true)`,
* cleans both up inside `ctx.effect(() => () => { tag.remove(); document.removeEventListener(...) })`,
* never `require`s anything and never registers into a slot.

Constraints that still apply:

* **HMR / unload.** Your entry's fiber is disposed on reload or removal
  (`client.js:707`), which runs every `ctx.effect` disposer in reverse order and
  then `removeOwnedStyles(id)`, which deletes every
  `style[data-plugin="<id>"]` regardless of your disposer. So: **always tag
  injected styles with `data-plugin="<your id>"`** — `claimStyles` auto-tags any
  untagged `<style>` present at materialization, but tagging explicitly is the
  contract.
* **No React, no slot.** If you want a *UI surface*, the slot system renders React
  elements — but `react` / `react/jsx-runtime` / `react-dom` are seed words, so a
  build-free plugin can use `React.createElement` (or
  `require("react/jsx-runtime").jsx`) and even `require("react-dom/client")` to
  create its own root on a node it appends to `document.body`. Nothing forces a
  bundler.
* **CSP.** DSH serves the shell with no CSP header that blocks inline scripts
  (the boot protocol itself injects an inline `<script>` for
  `window.__ModuleLoader__` and `__DSH_BOOT__`), and the bundle is
  same-origin under `/plugins`. No `unsafe-eval` needed.
* **CSS injection.** Either a tag you create (tag it `data-plugin`), or the
  `data-plugin-css` attribute if you manage several; `claimStyles` inventories
  them per module record.
* **`require` restrictions** apply to vanilla plugins too (§1.3).

---

## 6. Client-side cordis services

Extracted from the `declare module '@deepseek-ai/cordis' { interface Context { ... } }`
merges in every package that ships `lib/client.js` (`$DSH/*/lib/types/**/*.d.ts`).
`ctx.get("<name>")` and `exports.inject = ["<name>"]` take exactly these strings.

| Service | Provided by | Purpose |
| --- | --- | --- |
| `slots` | `dsh-client-ui-renderer` (`SlotRegistry`) | the slot registry (§4) |
| `uiRenderer` | `dsh-client-ui-renderer` | renderer install/mount face |
| `layout` | `dsh-client-ui-layout` (`ILayout`) | main-panel selection, sidebar/rightbar geometry, `toggleSidebar()` |
| `uiSession` | `dsh-client-ui-session` | current session lifecycle/identity |
| `uiConversation` | `dsh-client-ui-conversation` | conversation assembly |
| `conversation` | `dsh-client-ui-conversation` (`IConversation`) | conversation service face |
| `uiWorkspace` | `dsh-client-ui-workspace` | workspace UI state |
| `workspaces` | `dsh-api-workspace-controller` (`IWorkspaces`) | workspace list/selection |
| `workspaceController` | `dsh-api-workspace-controller` | controller |
| `workspaceFiles` | `dsh-api-workspace-files` | file tree / file ops |
| `directoryPickerController` | `dsh-api-workspace-controller` | directory picker |
| `sessions` | `dsh-api-session-controller` (`ISessions`) | session list |
| `sessionController` | `dsh-api-session-controller` | session controller |
| `sessionFileReferences` | `dsh-api-session-controller` | file references |
| `sessionSkillCatalog` | `dsh-api-session-controller` | skill catalog |
| `remote` | `dsh-api-remotes` / `dsh-api-gateway` (`ClientRemote`) | remote RPC |
| `typertGateway` | `dsh-api-gateway` | typed gateway |
| `connection` | `dsh-client-connection` (`HostConnectionHandle`) | host transport (fetch/SSE) |
| `locale` | `dsh-client-locale` (`LocaleRuntime`) | i18n runtime, `ctx.locale.register(...)` |
| `theme` | `dsh-client-ui-theme` (`ThemeRuntime`) | theme snapshots |
| `shortcuts` | `dsh-client-shortcuts` | keyboard shortcuts |
| `commandUi` | `dsh-client-ui-commands` | command palette runtime |
| `resources` | `dsh-client-resources` | resource protocol registry |
| `fileUpload` / `fileUploads` | `dsh-client-file-upload` | uploads |
| `inputTriggers` | `dsh-client-ui-input-trigger` | composer trigger registry |
| `chatFileMentions` | `dsh-client-ui-chat` | @-mentions |
| `feedbackUi` | `dsh-client-ui-message-feedback` | message feedback UI |
| `modelDirectories` | `dsh-client-ui-model-selection` | model directory resolver |
| `settingsSchema` | `dsh-client-ui-settings` | settings schema service |
| `configForms` | `dsh-client-ui-settings` | settings form renderer |
| `sidebarRight` | `dsh-client-ui-sidebar-right` | right panel controller |
| `sidebarRightTabs` | `dsh-client-ui-sidebar-right` | right panel tab registry |
| `documentPreviews` | `dsh-client-ui-sidebar-documentpreview` | document preview registry |
| `userQuestionPanels` | `dsh-client-ui-tool` | ask-user panels |
| `jobs` / `jobController` | `dsh-api-job-controller` | background jobs |
| `webTerminals` / `terminalController` | `dsh-api-terminal-controller` | terminals |
| `pluginRegistryProbe`, `pluginNavigation` | `dsh-client-ui-plugin-manager` | plugin manager surface |
| `productAnalytics` | `dsh-client-product-analytics` | analytics |
| `modules` | `dsh-client-modules` (`ClientModuleLoader`) | the client module table itself |
| `timer` | `dsh-cordis-client-runner` (`ClientTimerService`) | browser timer service |
| `cordisInspect`, `dynamicCordisRunner` | `dsh-cordis-client-runner` | cordis introspection / dynamic plugin runner |
| `sessionLogDownload` | `dsh-session-log-export` | session log export |
| `loader` | vendored `cordis-plugin-loader` | the browser Loader (`ctx.loader.entries()`, `.internal`) |

`ctx.loader`, `ctx.registry`, `ctx.events`, `ctx.reflect`, `ctx.fiber`,
`ctx.logger` are cordis built-ins rather than DSH services (`logger`, `events`,
`reflect`, `registry`, `root`, `baseUrl` are real properties of the `Context`
class — `$DSH/cordis/lib/types/context.d.ts:13-30` — so they bypass the inject
guard).

> **There is no `store` service.** `@deepseek-ai/dsh-client-store` has **no**
> `lib/client.js` and no `dsh` field (`$DSH/dsh-client-store/package.json`); it is
> one of the nine **seed words** and exports store *primitives* —
> `createSnapshotStore`, `defineStore`, `shallowEqual`, `notifySubscribers`,
> `SnapshotStore<T>`, `StoreHandle`, `SnapshotSelectorHook`
> (`$DSH/dsh-client-store/lib/types/index.d.ts`). Per-entry state is wired into a
> slot registration through its `store:` seat
> (`ui-slots/lib/types/index.d.ts` `StoreDecl`/`PropsStore`), and the
> `ui-renderer` layer mints/caches one instance per handle × scope.

**Does a plain function-form plugin get service access?** A plugin is always a
*fiber*, and `Plugin.Base.inject` is read for every shape
(`Registry.plugin` → `Inject.resolve(e.inject)`), so an object plugin with
`exports.inject` is fully supported. The `dsh-web-mobile-fix` comment is about
its own case — it exports only `apply` and therefore has **no declaration site**,
so `ctx.layout` would throw `cannot get property "layout" without inject`; it
uses `ctx.get("layout")` (wrapped in try/catch) instead. Two correct options:

* declare `exports.inject = ["slots", "layout", "locale", …]` → the fiber stays
  PENDING until all are active and `apply` runs with them available; or
* leave `inject` unset and use `ctx.get("layout")` for optional lookups
  (`undefined` when absent, never throws).

---

## 7. Client-plugin hot reload

### 7.1 The watcher

`$DSH/dsh-client-hmr` — node half (`lib/index.js`) + browser half (`lib/client.js`).

```js
/** Host transport for Web client graph changes and rebuilt bundles. One interval
 *  stat-polls every graph row's client bundle (polling by design: network mounts
 *  deliver no inotify events), reports changes through ctx.clientModules.rebuilt(id),
 *  and serves the /plugins/events SSE channel ... */
const inject = ["clientModules", "webServer"];
const Config = z.object({ pollIntervalMs: z.number().step(1).min(1).default(500) });
```

`apply()` (`dsh-client-hmr/lib/index.js:41-116`) keeps a `watched` map built from
`ctx.clientModules.artifactBaseline(row.id)` for every graph row, and every
`pollIntervalMs` (default **500 ms**) stats
`{mtimeMs, ctimeMs, size}`; on any change it calls `ctx.clientModules.rebuilt(id)`.
`ClientModuleRegistry.rebuilt` re-reads the bytes, computes a new
`artifactRevision`, recomposes the graph and notifies `onRebuilt` +
`onGraphChanged`. `dsh-client-hmr`'s browser half receives the frame over
`GET /plugins/events` (`content-type: text/event-stream`, first line
`: connected`, then `data: {"type":"graph",...}` / `data: {"type":"rebuilt","id":...,"rev":...}`).

### 7.2 What the page does with a `rebuilt` frame

`ClientEntries.reload(id, rev)` → `ClientEntries.replace` (`dsh-client-modules/lib/client.js`):

```js
async replace(entry, id, rev, generation) {
  this.index.invalidateForReplacement(id, rev);
  await this.modules.prefetch(id);
  await tearDownEntryFiber(entry);       // ← disposes the old fiber: every ctx.effect disposer runs
  removeOwnedStyles(id);                 // ← and every <style data-plugin="<id>"> is removed
  await this.modules.import(id, "", {}); // ← re-materializes the new bundle
  await entry.refresh();                 // ← re-runs apply() with a fresh context
  ...
}
```

So the full lifecycle is: **old `apply`'s disposers → new bundle materialized →
new `apply`**. No page refresh.

### 7.3 What a third-party `link:`-installed plugin needs

**Only the file write.** There is no build step to run and no watcher to start:

* The row must be in the boot graph (i.e. the bundle patch inserted it) — the
  watcher iterates graph rows.
* `dsh-client-hmr` must be mounted — it always is in the `web` bundle
  (`$DSH/dsh-web-app/cordis.patch.yml:202-203`, `- id: client-hmr`), and the live
  graph confirms it (`"id":"@deepseek-ai/dsh-client-hmr", ... "immediately":true`).
* Then `touch`/edit `lib/client.js` in the linked package and the new bytes are
  live within ~500 ms.

The system-prompt caveat about `pnpm run dev:web` concerns **DSH's own packages
inside a checkout**, whose `lib/client.js` is a *build output* of `tsdown` — you
need the source watcher to regenerate it. It does not apply to a third-party
plugin whose `lib/client.js` you author directly. Confirmed on this machine: the
running server is the installed npm package
(`node .../node_modules/@deepseek-ai/dsh/lib/bin.js web --no-open --port 3080`) and
there is no vite/tsdown watcher process, yet `dsh-client-hmr` is mounted and
serving `/plugins/events`.

`ClientEntryState` (`lib/types/client/entries.d.ts`) exposes
`{syncing, failures[]}` to page diagnostics, and a failed reload leaves the row
listed in the boot audit (`web boot: N entries failed to activate …`).

---

## 8. Minimal working plugin — exact file tree and contents

```
dsh-client-plugin-skeleton/
├── package.json
├── cordis.patch.yml
├── README.md
└── lib/
    ├── index.js      # node half
    └── client.js     # browser half (classic script)
```

### `package.json`

```json
{
  "name": "dsh-client-plugin-skeleton",
  "version": "0.1.0",
  "description": "Third-party DSH client (browser) plugin skeleton.",
  "license": "MIT",
  "type": "module",
  "main": "lib/index.js",
  "exports": {
    ".": "./lib/index.js",
    "./client": "./lib/client.js",
    "./package.json": "./package.json"
  },
  "files": ["lib", "cordis.patch.yml"],
  "engines": { "node": ">=20.0.0" },
  "dsh": {
    "bundle": { "patch": "./cordis.patch.yml" },
    "client": { "platform": "web" }
  }
}
```

Notes: no `peerDependencies` on `@deepseek-ai/dsh-*` (avoids the compatibility
skip, §2); `./package.json` is not required by DSH but keeps the manifest
inspectable; `main` must be a real module exporting something (an empty `apply`
is fine).

### `cordis.patch.yml`

```yaml
# No `id` here ⇒ the insert list is APPENDED to the top level of the entry list.
- insert:
    - id: client-plugin-skeleton          # the Loader entry id (free-form, unique)
      name: 'dsh-client-plugin-skeleton'  # module specifier — MUST resolve to this package
```

### `lib/index.js` (node half)

```js
/**
 * Exists only so the Loader has a module to mount for the inserted row, and so
 * the client-modules node half can find this package.json (and therefore
 * exports["./client"]) from the row specifier. Contributes nothing.
 */
export function apply() {}
```

### `lib/client.js` (browser half)

```js
window.__ModuleLoader__.load({
  id: "dsh-client-plugin-skeleton",     // MUST equal the package name
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var React = require("react");       // platform seed word
    var h = React.createElement;

    var CSS =
      ".dcps-fab{position:fixed;right:18px;bottom:18px;z-index:30;border:0;" +
      "border-radius:999px;padding:10px 16px;font:600 13px system-ui;cursor:pointer;" +
      "color:#0b0d12;background:#cfe0ff;box-shadow:0 10px 30px rgba(0,0,0,.35)}" +
      ".dcps-root{position:fixed;inset:0;display:flex;align-items:center;" +
      "justify-content:center;background:rgba(8,10,14,.72);color:#e8ecf3}";

    function Overlay() {
      var s = React.useState(false), open = s[0], setOpen = s[1];
      if (!open) {
        return h("button", { className: "dcps-fab", type: "button",
                             onClick: function () { setOpen(true); } }, "Skeleton plugin");
      }
      return h("div", { className: "dcps-root" },
        h("button", { type: "button", onClick: function () { setOpen(false); } }, "Close"));
    }

    function apply(ctx) {
      var style = document.createElement("style");
      style.setAttribute("data-plugin", "dsh-client-plugin-skeleton");
      style.textContent = CSS;
      document.head.append(style);
      ctx.effect(function () { return function () { style.remove(); }; },
                 "dsh-client-plugin-skeleton: stylesheet");

      // Wait for the declaration, then register. `id` is mandatory for `list`.
      ctx.slots.inject("shell.overlay", function () {
        return ctx.slots.register(
          { name: "shell.overlay", id: "skeleton-overlay", order: 50 }, Overlay);
      });

      var layout = ctx.get("layout");   // optional service, never throws
      ctx.logger.info("skeleton: layout " + (layout ? "available" : "absent"));
    }

    exports.apply = apply;
    exports.inject = ["slots"];
    return module.exports;
  },
});
```

### Install

```sh
cd ~/.dsh/profiles/web
pnpm add link:/abs/path/to/dsh-client-plugin-skeleton
# then ensure "dsh-client-plugin-skeleton" is in dsh.profile.bundles
# (dsh plugin --profile web install <spec> does both automatically)
```

Verify without booting:

```sh
dsh --profile web --patch /abs/path/to/dsh-client-plugin-skeleton/cordis.patch.yml --dump-config | tail -3
# == /abs/path/.../cordis.patch.yml
# - id: client-plugin-skeleton
#   name: dsh-client-plugin-skeleton
```

(That exact command was run against the real profile during this investigation —
exit 0, row appended at the top level, as shown.)

---

## 9. Gotchas

1. **`id` ≠ package name ⇒ silent-looking hard failure.** The row reports
   `could not load "<row id>": ... loaded without registering "<row id>" via __ModuleLoader__.load`.
2. **`dsh.client.platform` wrong or `dsh.client` missing ⇒ total silence.** The
   package is classified as “not a client package” and the browser half is never
   served. No warning, no error.
3. **`dsh.client` present but no `exports["./client"]` ⇒ loud boot failure**
   (`declares dsh.client but exports no "./client" bundle`).
4. **`dsh.bundle.patch` missing while listed in `dsh.profile.bundles` ⇒ the whole
   bundle is skipped** with `dsh: skipping profile bundle "<name>": profile bundle
   "<name>" declares no dsh.bundle in its package.json`.
5. **`peerDependencies` on `@deepseek-ai/dsh-*` with a mismatching range ⇒ bundle
   skipped.** Omit them, or use `"*"`.
6. **Bundle file must exist at boot**; it is read eagerly, not lazily.
   `client-modules: client bundle not found; run \`pnpm run build\` before launch`.
7. **`lib/client.js` must be a classic script.** `import`/`export` at top level is
   a parse error in the injected `<script src>` (no `type="module"`). Keep the
   ESM syntax in `lib/index.js` only.
8. **Side effects must be inside the factory.** Code at `<script>`-execution time
   runs before the module system exists; only the `load()` call may be top-level.
9. **The factory's return value must be an object with `apply`.** `isPlugin` rejects
   a bare function export.
10. **A `require` miss is fatal at materialization**: `require("foo") missed the
    module table…`. Only the nine seed words plus arrived graph rows exist.
11. **`insert` + `id` means “insert INTO that group”.** Putting your new row's id
    on the outer patch yields `patch insert: entry "<id>" not found`. The row id
    goes *inside* the inserted mapping.
12. **`name` in a non-insert patch is an assertion, not a rename** — a mismatch
    skips the patch with a warning, silently leaving the old value.
13. **`config` is replaced wholesale.** Repeat every key the row owns or you drop
    the rest (this is why `dsh-web-app/cordis.patch.yml` restates whole configs).
14. **A patch that matches nothing only warns** (exit 0). Silent no-ops are easy
    to miss; always confirm with `--dump-config`.
15. **A malformed patch *file* does throw** — non-array top level, or an `insert`
    that is not an array (the latter with a raw
    `TypeError: patch.insert?.forEach is not a function`).
16. **Register before the slot is declared ⇒ throws**
    `slot "<name>" is not declared (a parent entry's children table must declare it)`.
    Always wrap in `ctx.slots.inject(key, …)`.
17. **`single` slots allow only one occupant per priority.** A second
    priority-0 registration throws naming the occupant; use a distinct
    `priority` to shadow (lowest renders).
18. **`list` requires `id`, `keyed` requires `key`, `chain` requires `select`.**
19. **`root` is occupied by `AppFrame`.** Registering there replaces the whole app.
    Use `shell.overlay` for a frame-wide surface.
20. **Tag injected CSS with `data-plugin="<your id>"`** so HMR/entry replacement
    strips it (`removeOwnedStyles`) and so `claimStyles` attributes it correctly.
21. **`ctx.<service>` without declaring it in `inject` throws**
    `cannot get property "<name>" without inject`. Use `ctx.get(name)` for
    optional lookups (`undefined`, never throws, `strict` defaults to `true` so an
    inactive provider also reads as absent).
22. **A `dsh.client.inject` name that is not a boot-graph row is silently ignored**
    — it is an arrival edge, not a service declaration. Cross-package *services*
    go through the cordis `inject` export.
23. **The bundle URL must carry the exact advertised `rev`** (derived from
    mtime/ctime/size); anything else 404s.
24. **`require.async("./x")` is restricted** to `client.<name>.js` file names.
25. **The `bundles` list order is the patch-layer order.** A plugin that must
    override another bundle's row has to come later in `dsh.profile.bundles`;
    `dsh plugin install` appends, so it wins by default over earlier bundles but
    loses to the profile's own `cordis.patch.yml` and to `~/.dsh/cordis.patch.yml`.

---

## 10. UNCERTAINTIES

* **Empirical HMR of a third-party bundle was not exercised.** I verified the
  whole chain from code (stat-poll → `rebuilt()` → SSE `rebuilt` frame →
  `ClientEntries.replace` → fiber dispose + `removeOwnedStyles` + re-import +
  `apply`) and confirmed `dsh-client-hmr` is mounted and `/plugins/events` is
  streaming on the live server, but I deliberately did **not** `touch` the
  installed `dsh-web-mobile-fix`/profile files to avoid forcing a live reload of
  the user's running GUI. Treat “it reloads within ~500 ms” as code-derived, not
  observed.
* **`dsh.compatibility`** (`dsh: { compatibility: { dsh, dshReleases } }`) is
  present in both reference plugins but I could only prove that
  `dsh-app-boot`'s `evaluatePluginCompatibility` ignores it (it uses
  `peerDependencies` only). Whether the plugin-manager UI reads it for display is
  **not confirmed**; it appears to be informational.
* **The exact set of names a bundle may put in `dsh.client.external` is not
  validated at compose time.** Whether a *build-time* purity gate exists in the
  DSH source checkout (the comments reference “the runtime mirror of the
  build-time bundle purity gate”) cannot be verified from the installed package —
  only `dist`/`lib` artifacts are present, no `src/`.
* **No `pnpm` was executed** during this investigation. The `link:` behaviour is
  inferred from the working `dsh-preset-mobile-use` install (symlink in
  `node_modules`, `link:` entry in `pnpm-lock.yaml`) and from
  `dsh-plugin-manager`'s pnpm invocation code, not from running `pnpm add` myself.
* **`ctx.slot(...)` does not exist.** The API is `ctx.slots.register(...)` /
  `ctx.slots.inject(...)`. If some older document mentions `ctx.slot`, it is
  stale. (No occurrence of `ctx.slot(` was found in any installed bundle.)
* **Slot keys are an open string domain at runtime.** `SlotCore.entries(key)` /
  `specDynamic(key)` accept arbitrary strings; the compile-time `SlotMap` merge
  is what makes them typed. A plugin can therefore target a slot key that exists
  in a *newer* DSH than the one it was written against — and will get the runtime
  `slot "<name>" is not declared` throw rather than a type error, because a
  build-free JS plugin has no type checking.
* **`dsh --dump-config-schema`** was not run (it can mount discovery machinery);
  the patch schema in §3.3 is quoted from
  `dsh-app-boot/lib/index.js` (`metadata()`, `patchStructure()`, lines ~2726-2765)
  plus the live tests, which agree.
