# harness.apk

An Android-native harness for running Claude Code agents on-device.

## Premise

Claude Code ships amd64 and arm64 builds. The arm64 build runs on Android once the
binary is patched with a glibc↔bionic shim — see
[ferrumclaudepilgrim/claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android),
which also overrides Claude's DNS servers via a Bun preload because the shim breaks
resolution. Verified working.

Android can already run Claude Code and VS Code. Both are a hassle. That hassle is what
this project removes.

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
  inside the harness process. Confirmed working for env and DNS overrides.

Primary use is trip planning. Building code is the occasional case, not the design center.

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

It runs a shell on a pseudoterminal, so `tty` reports `/dev/pts/0`, `stty size`
matches the rendered grid, and full-screen programs work in raw mode on the
alternate screen. Keystrokes reach the process through an `InputConnection` on
the surface view, which reports `TYPE_NULL` so the IME keeps no editable buffer.

Control and Alt are offered as sticky modifiers, since a soft keyboard has
neither, so a foreground program can be interrupted. The surface draws only when
something changes rather than continuously.

Still missing on the terminal side: rotation is untested, and `libghostty-vt`'s
own key encoder stays unused in favour of a small encoder in the view.

### Building the renderer

The renderer requires OpenGL ES 3.1, which every current Android device has but
the emulator's software rasteriser does not: SwiftShader reports 3.0 and the
renderer will not initialise on it, so an emulator has to render on the host GPU.

`scripts/build-android-nonix.sh <abi>` builds both native libraries. It needs Zig 0.15.2,
patchelf and an Android NDK. The NDK's host toolchain is detected, so an NDK installed for
a different host OS works as long as its sysroot is readable. Gradle consumes the result
with `-PskipNativeBuild`, which fits a split where Zig runs in a Linux environment and
Gradle runs elsewhere.

## Open questions

- Compose is the default, not a commitment.
- The method surface an IDE must implement for `claude --ide` is not publicly specified.
  Reverse-engineering it against the CLI is the main risk in the architecture; how much of
  the platform glue the preload hook has to carry instead depends on what that surface
  turns out to be.
- The Claude binary needs the bionic/glibc shim for whichever ABI it targets. x86_64 is no
  easier than arm64.

## Distribution

The patched binary is downloaded on-device, not shipped in the APK.

## Status

A working terminal. No harness yet.
