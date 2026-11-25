# lib/web: the `z web` page

- `index.html`: the page, written with Preact components in htm tagged templates. There's no build step: edit the file and reload the browser.
- `htm-preact-standalone-3.1.1.mjs`: `preact/standalone.module.js` from the npm package `htm@3.1.1`. It bundles htm, Preact and Preact's hooks. It's vendored, not loaded from a CDN, so the page works offline and needs no third-party origin in its CSP. htm is Apache-2.0 and Preact is MIT.

To update, run `npm pack htm@<version>`, copy `package/preact/standalone.module.js` here under a name that includes the version, then update the file name in `lib/ZWeb.groovy` (`assets`).
