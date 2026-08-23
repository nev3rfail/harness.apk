# harness.apk

An Android-native harness for running Claude Code agents on-device.

## Premise

Claude Code ships amd64 and arm64 builds. The arm64 build runs on Android once the
binary is patched with a glibc↔bionic shim — see
[ferrumclaudepilgrim/claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android).
This is verified working, not theoretical.

So: a Jetpack Compose app that hosts a terminal, runs the agent on-device, and adds
the things a plain terminal can't do.

## Shape

- **App shell** — Jetpack Compose.
- **Terminal** — an embedded terminal emulator. Ghostty is the candidate: it has no
  Android target, so it needs a platform layer and a GLES renderer.
- **Agent** — the Claude Agent SDK driving the on-device binary. If the SDK can spawn
  the native TUI directly, use that. Otherwise wrap the harness via
  `BUN_OPTIONS="--preload our_preloader.js"` and intercept from inside.

## Capabilities beyond a terminal

- Render markdown as markdown, not as ANSI in a scrollback buffer.
- Hand URLs to the system: a map link opens a map, a video link opens a player.
- Embeddings.

## Open questions

Nothing here is settled.

- **Compose** is the default, not a commitment.
- **Ghostty** is a candidate. It cannot target Android as-is; the forks that tried may
  be dead. Alternatives worth weighing before committing: Termux's terminal-emulator
  library, or a Compose-native renderer over a VT parser.
- **Agent SDK vs. native TUI** are two different products. The SDK gives a structured
  event stream — which is what markdown rendering, URL dispatch, and embeddings actually
  need. The TUI gives a terminal. Decide which one is the product before building either.
- **`BUN_OPTIONS` preload** against a Bun single-file executable is unverified. Test it
  before designing around it.
- **Distributing a patched binary** inside an APK is a licensing question, not a
  technical one.

## Status

Idea stage. No code.
