# harness.apk

>A full agentic loop delivered to your phone

## Description

An Android app that runs Claude Code on the phone as a native process, and is
also the editor that agent attaches to.

Anthropic publishes a musl build of the agent, and a musl binary asks the system
for one thing Android lacks: `/lib/ld-musl-<arch>.so.1`, which on musl is libc
and the ELF interpreter at once. The app ships that loader, built from musl's own
release, one per ABI, and runs the agent as an argument to it. On x86_64 a
syscall tracer goes in front, because Android's seccomp allowlist covers only the
`*at` calls bionic uses while the agent's runtime reaches for the legacy ones;
aarch64 never had them.

The terminal is ghostty, drawn by its own renderer through JNI onto a GL surface.
Under it sits a relocated Termux userland -- bash, git and the rest -- with its
prefix inside the app's data directory. `apt install` works against Termux's
repository, because every archive is rewritten on the way in to land under that
prefix.

Being the editor is what makes the phone useful to the agent. Claude Code finds
one by reading a lockfile that names a port, so the app writes one and answers on
it. The tools an editor is asked for are fixed, so anything meant to be reached
for on purpose is served separately, as an MCP server the agent is configured
with. Either way, a capability the app can draw becomes a tool the agent can
call: a document rendered rather than printed, a diff held open until someone
accepts it, a map, or a URI handed to whichever app the phone opens it with.

## Restrictions

- targetSdk=28 and compileSdk=34
- markdown view is rather lacking
- since there are no rich interactive widgets (yet?) it is more like a proof of concept. But it works good enough

## Shoulders of giants we're standing on

- ghostty for being the best tty
- ghostty-android authors that did a lot of heavy lifting running it on an alien platform with an alien renderer
- claude-code-android for the inspiration with shimming glibc with bionic and in general showing me that it is possible
- termux for the userland, and it's wonderful community for providing provides third-party precompiled stuff like arm ndk navigation
