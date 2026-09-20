---
name: headless-browser
description: Use when an agent needs to visit a page it can drive itself -- fetch rendered HTML or visible text, screenshot a site, run a page through JS, or browse the open web -- rather than read a page the operator already has open. A real headless Chromium on this device, driven by Playwright over CDP. Ships the launcher and the CLI that drive it. For a page already open in the operator's Firefox, use firefox-remote-debug instead.
---

# Browsing the web from this device

A native headless Chromium runs here and a Node client drives it with Playwright.
It renders JavaScript, so it sees what a browser sees, not what `curl` gets. The
working tree is `$HOME/browsing`; the launcher and the CLI ship beside this skill
in `browsing/` and are copied there by the install.

This is for pages *you* go to. For a value out of a page the operator already has
open in Firefox, that is `firefox-remote-debug`.

## Why it is built this way

- Chromium comes from Termux's TUR (`tur-repo` + `x11-repo`); its command is
  **`chromium-browser`**, not `chromium`. It is a bionic aarch64 build, so no
  proot is involved.
- There is no root and no user namespaces, so it only starts with
  `--no-sandbox --headless=new`.
- Playwright's own bundled Chromium is a glibc build and will not run here. So
  `playwright-core` is installed **without** its browser download and used only
  to *drive* the native Chromium over CDP (`connectOverCDP`).
- Termux's Node reports `process.platform === "android"`, which playwright-core's
  registry refuses with `Unsupported platform`. The client spoofs it to `linux`
  before importing playwright. Keep that line first in any new script.

## The route

Chromium runs as a long-lived process exposing CDP on `127.0.0.1:9222`; the
client connects to that. Start it once, then drive it as often as you like.

```sh
# 1. Ensure the browser is up (idempotent: only starts if the port is down)
curl -s --max-time 2 http://127.0.0.1:9222/json/version >/dev/null \
  || { $HOME/browsing/chromium-headless.sh >$HOME/browsing/chromium.log 2>&1 & \
       until curl -s --max-time 2 http://127.0.0.1:9222/json/version >/dev/null; do sleep 1; done; }

# 2. Drive it
node $HOME/browsing/browse.mjs <url> [--text | --html | --shot [file.png]] [--wait <ms>]
```

- `--text` prints `document.body.innerText`; `--html` prints the rendered DOM;
  `--shot` writes a full-page PNG (default `shot.png`); no flag prints the title.
- `--wait <ms>` pauses after load for slow client-rendered pages.
- A screenshot is a file, not something the terminal shows. Hand it to the
  operator with `show_document_panel` (embed it) or `open_in_phone_app`.

Chromium does not survive a reboot. The ensure snippet starts it again, and only
when the port is down.

## Driving it directly

For anything past the CLI -- clicking, typing, waiting on a selector, reading a
specific node -- write a short script. The first line is not optional.

```js
Object.defineProperty(process, 'platform', { value: 'linux' }); // Termux quirk
const { chromium } = await import('playwright-core');
const b = await chromium.connectOverCDP('http://127.0.0.1:9222');
const ctx = b.contexts()[0] || await b.newContext();
const page = await ctx.newPage();
await page.goto(url, { waitUntil: 'networkidle', timeout: 45000 });
// ... page.click / page.fill / page.waitForSelector / page.evaluate ...
await b.close();   // closes YOUR connection, leaves Chromium running
```

Run such a script from `$HOME/browsing` so it resolves `playwright-core` out of
the tree's `node_modules`. The full Playwright page API is available; only the
launch/registry side is off limits, which is why you connect instead of
`launch()`.

## If Chromium is not installed

If `chromium-browser` is missing or `$HOME/browsing` is empty, it has not been
set up on this device yet. It is a one-time, ~300 MB install -- TUR + x11 repos,
the native Chromium, Termux Node, the two shipped scripts, and `playwright-core`
**without** its bundled (glibc) browser. The full sequence, and why each piece is
needed, is in [references/install.md](references/install.md). Ask the operator
before pulling it, then follow that file.

## Common mistakes

| Mistake | What is true |
|---|---|
| `chromium ...` | The command is `chromium-browser`; `chromium` is not found |
| `chromium.launch()` | The registry refuses `android`/glibc. Start the process, then `connectOverCDP` |
| Importing playwright before the platform spoof | Throws `Unsupported platform: android`. Spoof `process.platform` first |
| Running without `--no-sandbox` | No root here; it exits at startup |
| Typing `/data/data/<channel>/files` | Three channels run this skill. Spell paths `$HOME` and `$PREFIX` |
| `chromium-headless.sh: Permission denied` | The copy out of the skill carries content, not the execute bit. `chmod +x` it |
| Writing scratch to `/tmp` | Not writable. Use `$TMPDIR` |
| Treating dbus/GCM lines in `chromium.log` as failures | Harmless -- there is no dbus and no Play Services |
| `curl`-ing the page instead | You lose JS rendering; that is the whole point of this |
| Printing a screenshot path and stopping | The operator cannot see a file path. Show or share the PNG |
