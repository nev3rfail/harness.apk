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

[tapthaker/ghostty-android](https://github.com/tapthaker/ghostty-android) is the candidate
and it is further along than its own README claims — that README still says "research and
planning" while the code is at v0.8.1 with:

- a GLES renderer written in Zig (glyph atlas, font cache, shaders) over `libghostty-vt`
- `android/terminal-library` as a separate Gradle module, published as an AAR
- Android input handling: IME, touch, edge gestures, scroll position preserved across
  reflow
- a JNI viewport-text API added specifically to detect Claude Code in the terminal

**The gap: no pty.** `TerminalSession` spawns `/system/bin/sh` through `ProcessBuilder` and
pipes stdio. No tty means no raw mode, no `SIGWINCH`, no `isatty()` — an interactive TUI
will not run under it. Closing this means a `forkpty()` JNI shim; bionic has `forkpty`,
and Termux's terminal-emulator is the reference implementation.

Last push to ghostty-android was January 2026. If reviving it costs more than it saves,
embedding Termux's terminal-emulator instead is the fallback — mature, battle-tested, and
it already has the pty.

## Open questions

- Compose is the default, not a commitment.
- Ghostty vs. Termux for the terminal.
- Whether the `--ide` MCP surface is stable enough to build platform glue on, or whether
  the preload hook has to carry more of the load.

## Distribution

The patched binary is downloaded on-device, not shipped in the APK.

## Status

Idea stage. No code.
