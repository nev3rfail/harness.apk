# harness.apk

An Android-native harness for running Claude Code agents on-device.

## Premise

Android can already run Claude Code and VS Code. Both are a hassle. That hassle is what
this project removes.

Anthropic publishes musl builds of Claude Code, and a musl binary asks the system for one
thing Android lacks: `/lib/ld-musl-<arch>.so.1`. On musl that loader is also libc, and it
satisfies the binary's only `DT_NEEDED` from itself, so the whole port is one shared
library plus an ELF interpreter that points at it.

What is left is not linkage but sandbox. Three platform facts stand between a Linux binary
and an Android app, and the harness answers all three — see
[Running the agent](#running-the-agent).

[ferrumclaudepilgrim/claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android)
is vendored at `vendor/claude-code-android` as the reference for the same problem solved
inside Termux, where a full glibc runtime is available from a package manager. Checking the
download against Anthropic's published manifest is taken from it, as is the observation
that a runtime carrying its own resolver has to be pointed at a nameserver by hand.

## Architecture

The app is the IDE. Claude runs as `claude --ide` and connects to it.

Claude Code's IDE integration works by discovery: the editor writes a lockfile into
`~/.claude/ide/` naming its port, stands up an MCP server over WebSocket, and the CLI
connects as a client. Four methods the CLI calls itself; everything else an editor offers
is an ordinary MCP tool the model can reach for. That last part is the whole opening: a
capability the app can render becomes a tool the agent can call.

The protocol is not published. What it actually is, read out of the CLI's own bundle, is
written down in [docs/ide-protocol.md](docs/ide-protocol.md), and `tools/ide-probe.py`
speaks it well enough to exercise the app without a signed-in agent.

So the shell is the platform glue:

- **Terminal** — the agent's native TUI, unmodified.
- **Documents** — `showMarkdown` renders headings, lists, quotes, code, links and tables
  as a document instead of as terminal text.
- **Files and diffs** — `openFile` shows a file; `openDiff` shows a proposed edit and
  holds the tool call open until the edit is accepted or rejected.
- **Places** — `showPlace` puts a map on screen, from OpenStreetMap tiles, with no API
  key and no Play services.
- **Everything else the phone already does** — `openExternal` hands a URI to the system,
  so a `geo:` link opens maps and `tel:` opens the dialer. On Android nearly everything is
  an Activity, so this is mostly intent dispatch.

A panel gets a window of its own, because the terminal renders on a surface composited
above its own window and nothing drawn there can cover it.

Primary use is trip planning. Building code is the occasional case, not the design center.

## Running the agent

`scripts/stage-claude.sh` produces what the device needs: it builds the musl loader from
source, downloads the matching Claude Code build, checks it against Anthropic's manifest,
and sets the binary's ELF interpreter to where the loader will live on the device. Staged
files go in the app's own directory; nothing is shipped in the APK.

Three platform facts make the difference between that binary existing and running.

**Executability.** An app targeting API 29 or later may not `execve` anything in its own
data directory: the policy grants its domain no `execute_no_trans` on `app_data_file`. The
app targets 28, which is where Termux sits and for the same reason.

**Syscalls.** The seccomp policy for an app is an allowlist of what bionic calls, and
bionic only uses the `*at` variants. Everything else — `access`, `poll`, `pipe`, `dup2`,
`unlink` — is answered with `SECCOMP_RET_KILL_PROCESS`. On x86_64 the kernel still offers
the legacy calls and the agent's runtime uses them, some through libc and some directly.
`syscall-shim` traces the agent and rewrites them into their `*at` equivalents, which
works because the kernel runs the ptrace syscall-entry stop *before* it evaluates seccomp,
precisely so a tracer's changes are the ones the filter judges. It ships as a native
library, because that directory is executable whatever the app targets, and is unpacked
rather than mapped from the APK so there is a real file to execute.

The aarch64 Linux ABI has only the `*at` syscalls, which is exactly the allowlisted set,
so on a phone there is nothing to translate and the shim only execs. x86_64 needs the
translation, and x86_64 is the emulator.

**Name resolution.** Android resolves names through netd, so a runtime carrying its own
resolver finds no `/etc/resolv.conf` and falls back to localhost. The shim redirects paths
under `/etc` at a directory the app owns; the app writes the resolver file into it. This
points the agent's own lookups at public resolvers, which overrides a VPN or a local
resolver for those queries.

`tools/` holds the programs these conclusions were measured with, and the measurements.

Signing in is the agent's own OAuth flow, run once on the device.

### Building

The app is `:app`; the terminal comes in as `:terminal-library` from the vendored fork, so a
change there is one build away from running. Native libraries for the renderer are built
separately (see below) and consumed with `-PskipNativeBuild`:

```sh
./gradlew :app:installDebug -PskipNativeBuild
scripts/stage-claude.sh --prefix /data/data/apk.harness/files/claude
```

## Terminal

The terminal is [nev3rfail/ghostty-android](https://github.com/nev3rfail/ghostty-android),
a fork of [tapthaker/ghostty-android](https://github.com/tapthaker/ghostty-android),
vendored at `vendor/ghostty-android`. It brings:

- a GLES renderer written in Zig over `libghostty-vt` (glyph atlas, font cache, shaders)
- `android/terminal-library` as a Gradle module, publishable as an AAR
- Android input handling: IME, touch, edge gestures, scroll position preserved across
  reflow

On an Android 15 emulator it renders a 66x43 grid at 60 fps, around 1 ms per frame, with
bold, dim, italic, underline, reverse video and strikethrough all correct.

It runs on a pseudoterminal, so `tty` reports `/dev/pts/0`, `stty size` matches the
rendered grid, and full-screen programs work in raw mode on the alternate screen.
Keystrokes reach the process through an `InputConnection` on the surface view, which
reports `TYPE_NULL` so the IME keeps no editable buffer.

Control and Alt are offered as sticky modifiers, since a soft keyboard has neither, so a
foreground program can be interrupted. The surface draws only when something changes
rather than continuously.

Still missing on the terminal side: rotation is untested, and `libghostty-vt`'s own key
encoder stays unused in favour of a small encoder in the view.

### Building the renderer

The renderer requires OpenGL ES 3.1, which every current Android device has but the
emulator's software rasteriser does not: SwiftShader reports 3.0 and the renderer will not
initialise on it, so an emulator has to render on the host GPU.

`scripts/build-android-nonix.sh <abi>` in the fork builds both native libraries. It needs
Zig 0.15.2, patchelf and an Android NDK. The NDK's host toolchain is detected, so an NDK
installed for a different host OS works as long as its sysroot is readable. Gradle
consumes the result with `-PskipNativeBuild`, which fits a split where Zig runs in a Linux
environment and Gradle runs elsewhere.

## Next

1. **Let the agent drive the surfaces.** Every tool is verified against `ide-probe.py`, but
   an agent choosing to call them needs a signed-in session, which is a login on the
   device.
2. **Send the other direction.** `selection_changed` and `at_mentioned` are implemented and
   unused: nothing in the app yet lets a person select text or point the agent at a file.
3. **Give the agent a userland.** Its shell is Android's, which is toybox and no more, so
   the Bash tool has no `git` and no `curl`. What the harness offers as MCP tools covers
   part of that; the rest is a decision about how much of a Linux userland to carry.

Loose ends worth closing along the way: the renderer's native libraries are only built for
x86_64 locally, arm64 staging is untried because Google's emulator refuses an arm64 guest
on an x86_64 host, and rotation is untested.

## Open questions

- Compose is the default, not a commitment.
- The method surface an IDE must implement for `claude --ide` is not publicly specified.
  Reverse-engineering it against the CLI is the main risk in the architecture; how much of
  the platform glue the preload hook has to carry instead depends on what that surface
  turns out to be.
- The syscall shim costs two context switches per syscall on x86_64. Whether that matters
  is unmeasured, and it is unnecessary on the architecture a phone actually runs.

## Distribution

The agent binary is downloaded and staged on-device, not shipped in the APK.

## Status

Claude Code runs on the terminal, on Android, rendered by ghostty. The IDE it connects to
is the app: markdown, files, diffs, maps and intent dispatch, each verified against a
client that speaks the same protocol the CLI does.

What is left is an agent that has signed in and can choose to use them.
