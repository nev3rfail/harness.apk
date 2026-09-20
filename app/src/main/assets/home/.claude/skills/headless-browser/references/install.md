# Installing the headless browser on this device

One-time, and large (~300 MB of packages, pulling in gtk3 and the X11 stack --
about 154 packages). Data is unmetered here, but it is still a long download, so
say what you are about to do before starting it. Everything ends up usable by the
app's own uid; nothing needs root.

## 1. Repositories

Chromium is **not** in Termux's main repo. It lives in TUR (the Termux User
Repository), and TUR's chromium depends on GTK/X11 libraries that live in
`x11-repo`. Add both, then refresh:

```sh
apt install -y tur-repo x11-repo
apt update
```

## 2. Chromium + Node

```sh
apt install -y chromium nodejs
```

- The chromium package is a **bionic aarch64** build, so it runs directly -- no
  proot, no glibc shim. Its command is **`chromium-browser`** (a launcher
  script), *not* `chromium`.
- It links GTK/X11 even for headless use; that is why `x11-repo` is required.
- `nodejs` is Termux's build (v26+), and it runs bionic-native too.

Verify:

```sh
chromium-browser --headless=new --no-sandbox --version   # -> Chromium 149.x
node -v                                                   # -> v26.x
```

## 3. The working tree

The launcher, the CLI and the package manifest ship beside the skill. Copy the
tree to `$HOME/browsing` and give the launcher its execute bit:

```sh
cp -r $HOME/.claude/skills/headless-browser/browsing $HOME/browsing
chmod +x $HOME/browsing/chromium-headless.sh
```

The copy out of the assets carries content and not permissions, which is what the
`chmod` is for. The launcher's shebang is `/system/bin/sh` -- an absolute path
that resolves on every channel, where `#!/usr/bin/env` resolves on none. It puts
the chromium profile beside itself, so the tree is relocatable and no channel id
is written down anywhere.

## 4. Playwright -- core only, no browser download

Install `playwright-core`, never the full `playwright`. The full package tries to
download its own Chromium build, which is compiled for **glibc** and will not run
on this bionic device -- a wasted download that then fails to launch. We drive the
**native** Chromium instead, over CDP, so the bundled browser is pointless. Skip
the download explicitly:

```sh
cd $HOME/browsing
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install
```

`playwright-core` is the same API as `playwright` with the browser-fetching
machinery left out -- exactly what `connectOverCDP` needs. The shipped
`package-lock.json` pins the version. `package.json` declares
`"type": "module"` so that a plain `.js` file written in this directory is read
as a module too; a `.mjs` file is one by its extension whatever the field says.

## 5. The Termux platform quirk

Termux's Node reports `process.platform === "android"`. playwright-core's
registry refuses that with `Error: Unsupported platform: android`, and it throws
at *import* time -- before you ever call anything -- even though connecting over
CDP touches none of that machinery. Defeat it by spoofing the platform to `linux`
before importing Playwright, so the import must be dynamic:

```js
Object.defineProperty(process, 'platform', { value: 'linux' });
const { chromium } = await import('playwright-core');
```

Keep that pair as the first two lines of any script. Puppeteer-core, if you ever
prefer it, needs the same spoof.

## 6. Check it end to end

```sh
$HOME/browsing/chromium-headless.sh >$HOME/browsing/chromium.log 2>&1 &
until curl -s --max-time 2 http://127.0.0.1:9222/json/version >/dev/null; do sleep 1; done
node $HOME/browsing/browse.mjs https://example.com --text
```

## 7. What the tree holds

- `$HOME/browsing/chromium-headless.sh` -- starts Chromium headless with CDP on
  `127.0.0.1:9222`, `--no-sandbox --headless=new`, its own `profile/` dir.
- `$HOME/browsing/browse.mjs` -- the CLI the skill documents.
- `$HOME/browsing/package.json`, `package-lock.json` -- the one dependency.
- `$HOME/browsing/node_modules/` -- just `playwright-core`.
- `$HOME/browsing/profile/` -- the chromium profile, created on first start and
  the largest thing here.

Chromium is a long-lived process, not restarted per page, and it does **not**
survive a device reboot -- re-run `chromium-headless.sh` (the skill's ensure
snippet does this only when the port is down).
