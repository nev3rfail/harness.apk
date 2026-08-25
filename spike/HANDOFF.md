# Handoff

Read `README.md` for what the project is, and `docs/ide-protocol.md` for the part
documented nowhere else. This file is what the repo cannot hold: the state of the
devices, what is verified, what is open, and the traps that already cost time.

`harness.apk` at `732f215`, `vendor/ghostty-android` at `fabed00`. Neither is
pushed. Working trees clean, apart from a `zig-pkg/` build artifact the renderer
build leaves inside the `libghostty-vt` submodule.

Design notes live beside this file, all gitignored:

- `MORNING.md` -- the current decision queue and the evidence behind it. Read
  this second.
- `termux-reuse.md`, `toolbox.md`, `workspace-design.md` -- the three
  investigations, each reporting work built and run on real devices.
- `NIGHT-LEDGER.md` -- decisions taken without the partner present, and why.

## Where things stand

Claude Code runs on a phone and an emulator: signed in, reaching the API,
attached to the app as its editor, and calling the app's tools. Verified live:

- A file edit opens the app's **Review edit** panel and blocks until someone taps
  Apply or Reject. The agent never knows that tool exists; the CLI drives it.
- Asked where a place is, the agent **chooses** `show_map_location_panel` over
  MCP and the phone draws the map.
- Shift-Tab cycles the permission mode, both directions.
- **A login can be completed on the device**, end to end, on the real flow.

## Logging in

This used to be impossible on the device and is the reason credentials were
transplanted from the desktop by hand. Three pieces make it work, and none of
them required the CLI to cooperate.

**The URL is a link.** The agent cannot open a browser here -- it execs
`xdg-open`, which does not exist -- so it prints the authorization URL and marks
it up as an OSC 8 hyperlink for any terminal that claims to handle them.
`FORCE_HYPERLINK` in the agent's environment is that claim, and it is an honest
one: the fork already carries OSC 8 from the VT's hyperlink set through to a tap
callback. A tap hands the URL to whatever the phone opens links with. The whole
six-line wrapped URL is one link, query string intact.

**Selection copies.** Long-press and drag selects; releasing puts the text on the
Android clipboard.

**A clipboard key pastes.** The toolbar's clipboard key types the clipboard into
the terminal, which is what finishes the login: the authorization page answers
with a code, and it has to get back into the agent. Control characters are
dropped and lines joined with a space, because a pasted newline is Enter and
would run whatever the paste happens to contain.

**`c` copies too.** The CLI's own copy chord writes OSC 52, which the fork now
decodes and hands to the app. Verified by finding `ESC]52;c;<base64>` in the
captured stream and reading the same URL back out of the clipboard.

What the CLI actually does, so nobody re-reads `cli.js` for it:

- One opener for every platform: `process.env.BROWSER` or `xdg-open`, spawned
  with the URL as its only argument. `BROWSER` is therefore a hook, but a useless
  one here -- see the next point.
- The callback server is **always** started, and two URLs are built: a manual one
  redirecting to `platform.claude.com/oauth/code/callback`, and an automatic one
  redirecting to `http://localhost:<port>/callback`. The **manual** URL is the
  one displayed, unconditionally; the automatic one is only ever handed to the
  browser opener. So faking a successful browser open gains nothing: what can be
  tapped is always the manual URL, and its answer is a code to paste.
- OSC 52 is emitted on this platform whatever happens. The `tmux` branch nearby
  is for when tmux would otherwise eat the sequence.
- `am start` is **denied** inside the app's sandbox -- `Failure calling service
  activity: Failed transaction` -- so no shell shim can open a browser.

One lead left unbuilt: the CLI answers a `claude_authenticate` control request
that hands the caller *both* URLs and skips the browser open, alongside
`claude_oauth_callback` and `claude_oauth_wait_for_completion`. That would make
login a single tap with no code to paste, because the loopback redirect would
land in the CLI's own server and Android apps share the loopback namespace. It
arrives on the SDK's stream-json channel, though, which is not the interactive
TUI this app runs, so it is only reachable if the app ever drives the agent that
way.

## The decision queue

`MORNING.md` has the detail. In short:

1. **The application id is settled.** The length constraint on relocating a
   Termux bootstrap is 31 bytes for the whole prefix, not 21 for the data
   directory, and the component below the data directory is this app's to name:
   `/data/data/apk.harness/root/usr` replaces
   `/data/data/com.termux/files/usr` byte for byte. `apk.harness` for stable and
   `dev.harness` for the channel that iterates on it, no padding, verified up to
   a clone over https.
2. **Userland foundation.** Three independent reviewers unanimously recommended
   the own-prefix toolbox's cheap half -- busybox, bash, git without networking
   -- over the Termux bootstrap. That recommendation was priced against a Termux
   path carrying a rename and a hosted repository, and it carries neither, so the
   arithmetic wants redoing. What survives is their real objection: a standing
   interception inside somebody else's package manager.
3. **The agent's libc is a separate question from the userland.** Anthropic
   publishes `linux-arm64-musl`, and the port of it is one shared library.
   Running the agent from the Termux userland instead means `glibc-runner` out of
   a second repository, whose own installer smoke-tests every release because
   some crash on its `epoll_pwait2` shim. `vendor/claude-code-android` is that
   path, working, if the musl target ever goes away.
4. **The workspace design** is ready to become a spec.

## The terminal

Whatever the agent draws, the app shows. The renderer follows the active screen,
so a program that switches to the alternate screen is rendered there.

That switch was the long-standing "dead terminal": the renderer read the primary
screen unconditionally, so after the alternate-screen sequence it kept showing
the picture from before the switch -- usually the trust prompt, which is why it
bit right after a menu was answered -- while the cursor, which follows the active
screen, went on moving over it. `spike/pty.log` holds the stream that proves it.

Three other things were fixed on the way, each a real bug and none of them this
one: one VT parser for the terminal's life rather than one per read; a deadline on
synchronized output; and a follow-up frame after output, because
`RENDERMODE_WHEN_DIRTY` gives no further frame to a program that stops writing
mid-update.

The stream now runs through the app's own handler, which wraps the terminal's and
watches for the commands the terminal deliberately ignores. OSC 52 is the only
one taken so far; `window_title`, `progress_report` and
`show_desktop_notification` arrive at the same place and all have obvious homes
on a phone.

Replaying a captured stream through a reference VT is how to tell an agent bug
from a terminal bug:

    /tmp/vt-env/bin/python spike/replay.py /tmp/pty.log     # pyte, in WSL

`MainActivity` writes every byte the agent emits to `files/pty.log` when
`files/capture` exists. Create that file, restart, reproduce, and then
**`python3 spike/ptylog.py [device] [package]`** prints it with the escapes
stripped. That is the way to tell what screen the agent is on: it is text, it is
greppable, and it does not depend on a screenshot landing at the right moment.

## Driving a device without guessing

Screenshots of a GL surface come back stale often enough to mislead. Twice a
screen was called frozen when it had already advanced. Compare hashes rather than
file sizes, and prefer the pty log over the picture whenever the question is
"what is the agent showing".

`adb shell input tap` sometimes lands as a long press on a slow emulator, which
starts a selection instead of clicking. `input draganddrop x1 y1 x2 y2 duration`
is a long-press drag, which is exactly the selection gesture.

`input text` drops characters. Type short strings, or push a script and type its
name.

## The devices

**Phone: moto g15 power, arm64-v8a, Android 15.** It roams between hotspots, so
its address is not fixed: `bash spike/phone.sh` prints its adb serial, trying
known addresses and then sweeping the subnets. Only network serials count, so the
emulator is never mistaken for it.

Its display sometimes refuses to wake -- dozing, and ignoring the wakeup keycode
-- and plenty can be verified without one: the process table, the app's own
files, and `.claude.json`, which carries `oauthAccount` only after a successful
call home. What cannot be done without a screen is drive the terminal. Writing to
`/proc/<pid>/fd/<n>` does not help: the link points at `/dev/ptmx` and opening it
allocates a new pty rather than reattaching to the app's.

`ps -o cmd` on this phone prints just `claude`, not the path, so the two channels
are told apart by uid rather than by command line.

Two global settings were changed to get the first install through and never
restored: `verifier_verify_adb_installs` and `package_verifier_enable`, both set
to 0.

On the phone, stay inside the app under test. Two captures have caught private
messages, once a conversation and once the notification shade after a swipe from
the top edge. Never swipe from an edge, never open the shade. If the shade is
already open -- it has been -- dismiss it with the back keycode and take no
screenshot until the focused window is the app.

**Emulator: `harness_a15_x64`, Android 15, x86_64.** Runs with `-gpu host`. Its
`config.ini` has `hw.keyboard = no`, and Gboard on that image presents a window
the framework reports as shown and drawn at zero size, so there is no usable soft
keyboard. Hardware keycodes do reach the app correctly, so `hw.keyboard = yes`
plus a restart is the fix; the emulator rewrites `config.ini` on exit, so change
it while the emulator is down.

**The emulator mirrors the host clipboard.** Anything copied on either side
appears on the other, so a clipboard test there proves nothing about the app --
one already passed for the wrong reason. Clipboard work is verified on the phone.

It also carries a throwaway `apk.hrness` with a relocated Termux bootstrap at
`files/usr` and toolbox binaries at `files/tb`. With no agent staged it spawns a
plain shell in the app's own sandbox, which is the rig for anything `run-as`
cannot answer. Disposable: `adb uninstall apk.hrness`.

## Driving an app's terminal without a screen

With no agent staged, the app spawns a plain shell in its own sandbox, reporting
`context=u:r:untrusted_app_27`. Push a script, copy it into the app's files with
`run-as`, type its name into the terminal with `input text` and `keyevent 66`,
and read the answer back out of a file. In `input text`, a space is written `%s`.

Quote the remote command. An unquoted redirect belongs to the device's shell, so
redirecting the output of `input text` writes a file instead of typing the
character.

## Two channels

`dev.harness` is where work happens and `apk.harness` is the one that has to keep
working; both can be installed at once. The plain name belongs to stable because
that is the channel a userland has to fit under: a relocated Termux tree needs a
prefix of at most 31 bytes, and `apk.harness.stable` left four, which is not
enough for a rootfs directory and a `usr` below it.

A build type carries a suffix rather than an id, and `dev.harness` is not a
suffix of `apk.harness`, so the debug variant takes its id through
`androidComponents`. A `harnessAppId` property still overrides both, for
throwaway builds.

A different application id means a different data directory, and the musl loader
has its prefix compiled in, so each channel needs staging built for its own
prefix -- pass `--prefix` to `scripts/stage-claude.sh` and deploy with
`spike/deploy-stage.sh`. The same binding applies to everything else staged: the
userland tree, and a toolbox built for one channel reports permission errors
under another.

A login survives a reinstall or a rename: `spike/salvage-auth.sh save` streams
the agent's home out through `run-as`, and `restore` puts it back, rewriting the
recorded home as text when the id changed.

## Building

The renderer is not built by Gradle. A skip-native-build property is always
passed; the renderer is built in WSL:

    ZIG=/opt/zig-0.15.2/zig \
    ANDROID_NDK_ROOT=/mnt/d/Users/nev3rfail/AppData/Local/Android/Sdk/ndk/29.0.14206865 \
    bash vendor/ghostty-android/scripts/build-android-nonix.sh <abi>

for `x86_64` and `arm64-v8a`; `armeabi-v7a` has no renderer and no 32-bit device
needs one. An ABI with no renderer means the app dies loading its native library.

Gradle runs by invoking the wrapper's main class directly with the Eclipse
Adoptium 17 JDK:

    java -classpath gradle/wrapper/gradle-wrapper.jar \
      org.gradle.wrapper.GradleWrapperMain :app:assembleDebug -PskipNativeBuild

`:app:assembleStable` for the other channel.

## Traps that already cost time

**`run-as` is not the app.** Different SELinux domain, different seccomp filter.

**`zig cc` cannot build musl.** It is itself a musl toolchain, so musl's own
`memcpy`, `memset` and libm never reach the dynamic symbol table. Use
`aarch64-linux-gnu-gcc` or `gcc`.

**The loader carries the prefix.** libc opens the resolver file from inside
itself, so `stage-claude.sh` compiles the staged path in. This is also true of a
static build: static linking bakes the path in rather than escaping it.

**Never round-trip `.claude.json` through PowerShell's `ConvertTo-Json`.** It
mangles single-element arrays and nulls, and the agent then renders an empty UI
from a config it cannot read. Edit it with `python3` or `org.json`.

**`python` on PATH is Windows Python.** It cannot open MSYS-style paths from Git
Bash. Use drive-letter paths, or `wsl -e python3`. Git Bash's own `python3`
handles the repo's relative paths, which is the least friction for edit scripts.

**Write scripts as files, and write them with the editor.** Quoting a shell
one-liner through PowerShell fails on braces, commas and redirects, and a
heredoc through the Bash tool eats backslashes, which quietly breaks any regex
in it.

**`adb push` from git-bash needs both halves.** `MSYS2_ARG_CONV_EXCL` set to an
asterisk stops MSYS rewriting the device path -- and stops it rewriting the local
path too, so the local side must then be spelled Windows-style with
`cygpath -m`. Setting only one fails in a way that reports success. `wsl -e`
needs the same treatment, or the script path is rewritten before WSL sees it.

**PowerShell splits an unquoted Gradle property.** Quote them.

## What a first run needs

The app seeds `.claude.json` with `hasCompletedOnboarding` when the file is
absent, which skips theme and login. A login can now be done on the device, so
credentials no longer have to be copied from the desktop -- tap the URL, sign in,
copy the code the page gives back, and paste it with the clipboard key.

Trust and the MCP-server approval are answered once on the device, or seeded per
`workspace-design.md`.

## Standing instructions from the partner

Beyond the global rules in `CLAUDE.md` (evergreen prose, no `Co-Authored-By`,
push back rather than agree, spec before code):

- Decide and act. Do not stop to ask what can be measured instead.
- Verify your own work. Installing a build and asking whether it works is an
  unfinished task: launch it, drive it, read the screen, report what happened.
  That includes the phone.
- Look for prior art before pricing a build from scratch.
- Poll for the thing you are waiting on; never pad a sleep.
- Everything lives in the repo. Spikes go in `spike/`, which is gitignored.
- The fork is pushed to and meant to stay upstreamable, so agent-specific
  knowledge belongs in `app/`. Terminal capability, like OSC 52, belongs in the
  fork.
- Not everything installed is on PATH. The JDK that works is the Eclipse Adoptium
  17 under `Program Files`.
- The emulator runs with a window. The partner likes to watch.
- The first goal is that this is fun.
