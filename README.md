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
inside Termux, where a full glibc runtime is available from a package manager. It is the
source of the DNS workaround and of the practice of checking the download against
Anthropic's published manifest.

## Architecture

The app is the IDE. Claude runs as `claude --ide` and connects to it.

Claude Code's IDE integration works by discovery: the editor writes a lockfile into
`~/.claude/ide/` with its port and workspace, stands up an MCP server over WebSocket, and
the CLI connects as a client and calls into it. So the harness registers as an IDE, and
every capability the app wants to offer is an MCP tool the agent can call.

That makes the shell the platform glue:

- **Terminal** — the agent's native TUI, unmodified.
- **IDE surface** — file viewing and diffs rendered by the app, not by ANSI in a
  scrollback buffer.
- **Content embeds** — the agent asks the app to render something, and the app resolves it
  to a native widget or an Activity. A map link becomes a map. On Android nearly
  everything is an Activity, so this is mostly intent dispatch.
- **Bun preload** — `BUN_OPTIONS="--preload ..."` for anything that has to be patched
  inside the harness process.

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

1. **Register the app as an IDE.** Write a lockfile into `~/.claude/ide/`, stand up an MCP
   server over WebSocket, and let `claude --ide` connect to it. The method surface an IDE
   has to implement is not publicly specified, so this starts as reverse engineering
   against the CLI.
2. **Turn capabilities into MCP tools.** File viewing and diffs rendered by the app, and
   content embeds that resolve a request to a native widget or an Activity.
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

Claude Code runs on the terminal, on Android, rendered by ghostty. The harness itself —
the IDE surface the agent talks to — is not built yet.
