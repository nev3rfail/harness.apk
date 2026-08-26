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
solves the same problem inside Termux, where a full glibc runtime is available from a
package manager. Checking the download against Anthropic's published manifest is taken from
it, as is the observation that a runtime carrying its own resolver has to be pointed at a
nameserver by hand.

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
translation, and x86_64 is the emulator. Confirmed both ways: the agent runs unshimmed on
an arm64 phone and shimmed on an x86_64 emulator.

**Name resolution.** Android resolves names through netd, so a runtime carrying its own
resolver finds no `/etc/resolv.conf` and falls back to a nameserver on loopback that
nothing answers. Every lookup then spends its full timeout before failing.

The agent resolves names two ways and both have to be answered. `fetch` -- which is every
API call -- goes through libc, and libc opens that path from inside itself, by calls that
never reach the PLT, so nothing outside the library can redirect them. The loader is
therefore built with the staged directory compiled in, and the app writes the resolver
file there. The `dns` module goes through the runtime's own c-ares instead, which is named
directly by a preload the app writes and points `BUN_OPTIONS` at.

Both are fed from one list, so they cannot drift apart. This sends the agent's lookups to
public resolvers, which overrides a VPN or a local resolver for those queries and for
nothing else on the device.

**Two ways in.** The app is both the editor the agent attaches to and a server it is
configured with, because the two carry different things. The editor channel is what the
CLI drives as it works -- a diff to review before a file changes, diagnostics around it --
and of everything an editor offers, the CLI tells the agent only that `getDiagnostics`
exists. So the panels the agent should reach for on purpose are served over MCP instead,
where every tool listed is one it may choose. Same implementations, two transports.

`tools/` holds the programs these conclusions were measured with, and the measurements.

Signing in is the agent's own OAuth flow, run once on the device.

### Building

The app is `:app`; the terminal comes in as `:terminal-library` from the vendored fork, so a
change there is one build away from running. Native libraries for the renderer are built
separately (see below) and consumed with `-PskipNativeBuild`:

```sh
./gradlew :app:installDebug -PskipNativeBuild
scripts/stage-claude.sh --abi arm64-v8a --prefix /data/data/apk.harness/files/claude
```

Building the loader for another architecture takes a cross compiler -- `aarch64-linux-gnu-gcc`
for a phone, overridable as `CC`. `zig cc` is not one for this: it is itself a musl
toolchain, so building musl with it leaves musl's own `memcpy`, `memset` and libm out of
the symbol table, and the result fails on the device with nothing but `symbol not found`.
`verify_loader` gates the build on exactly those symbols.

The prefix is compiled into the loader as well as into the binary's ELF interpreter, since
that is where libc reads its resolver from, so a loader is only good for the prefix it was
built for. `--loader` takes one built elsewhere, which runs but resolves no names.

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

1. **Give the panels somewhere to point.** The agent picks a panel and the app draws it;
   what a phone still lacks is a way to answer back -- tapping a place on the map, picking
   one of three options -- which the diff review already shows the shape of.
2. **Send the other direction.** `selection_changed` and `at_mentioned` are implemented and
   unused: nothing in the app yet lets a person select text or point the agent at a file.
3. **Give the agent a userland.** Its shell is Android's, which is toybox and no more, so
   the Bash tool has no `git` and no `curl`. What the harness offers as MCP tools covers
   part of that; the rest is a decision about how much of a Linux userland to carry.

Loose ends worth closing along the way: rotation is untested, and a first run still needs
its credentials and its onboarding flag placed by hand.

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

Claude Code runs on the terminal, on Android, rendered by ghostty -- on an emulator and on
a phone, signed in, reaching the API. The app is the editor it attaches to and the server
it calls: a file edit opens a review the agent waits on, and asked for somewhere in
particular, the agent reaches for the map itself and the phone draws it.
