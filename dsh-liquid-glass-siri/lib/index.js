/**
 * Node (host) half of dsh-liquid-glass-siri.
 *
 * The plugin is a pure browser surface: the Loader only needs a real module to
 * mount for the row this package's cordis.patch.yml inserts, and the
 * client-modules node half needs to resolve this package.json to find
 * `exports["./client"]`. Nothing runs here.
 *
 * The row's `config` object is handed to the browser half as `apply(ctx,
 * config)` — see DEFAULTS in lib/client.js for every supported key.
 */

/** Host plugin body — intentionally empty. */
export function apply() {}
