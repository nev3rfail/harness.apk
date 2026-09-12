# harness.apk -- From Tinkerers For Tinkerers

>A full agentic loop delivered to your phone as a drop-in single apk

## Description

- Puts an agent into an apk file, that allows agent to interact with the phone: show you diffs when proposing edits, open files, show you things on the map. The apk bootstraps it's own termux environment for the agent to use, and installs several shims for the musl claude to work. Untested with other CLI harnesses, although you can (and should!) try to put your favorite Codex or Pi in agent.sh and see for yourself.
- With self adb, possibilities are endless: run as different programs, deploy programs written on android from said android to said android. With Firefox USB Debugging enabled, agent can even drive your mobile Firefox instance

## How it boots

```mermaid
%%{init: {'flowchart': {'wrappingWidth': 540}}}%%
flowchart TB
  l["launcher.sh<br/>assembles the platform; no abspath used here and below"]
  a["agent.sh<br/>names the command, its flags; uses bun preload hack to get dns working"]
  s["syscall-shim<br/>unpacked into the native library directory"]
  ld["ld-musl-&lt;arch&gt;.so.1<br/>run as a program, not named as PT_INTERP"]
  c["claude --ide<br/>launching a pristine claude binary from the upstream"]

  l --> a --> s --> ld --> c

  sdk["targetSdk 28 — API 29 and later grant an app<br/>no right to execute its own data directory"]
  abi["x86_64 — the legacy calls rewritten to their *at forms,<br/>at a ptrace stop the kernel runs ahead of seccomp.<br/>aarch64 — the ABI is already the allowlist, so the shim only execs"]
  dns["names — the staged prefix is compiled into the loader, so libc<br/>finds a resolver file; the dns module goes through c-ares,<br/>named by a preload that BUN_OPTIONS points at"]

  abi -.-> s
  sdk -.-> ld
  dns -.-> c

  classDef note fill:#fbfbfb,stroke:#999,stroke-dasharray:4 3,color:#444
  class sdk,abi,dns note
```

## Restrictions

- It is modern android. We can't achieve a true persistence, so claude should be instructed to be careful with background jobs and heavy tasks. Session that spawns 333 shells with `echo true` **will** be killed by the system immediately
- targetSdk=28 and compileSdk=35
- not enough interactive widgets (only map), so it is more like a proof of concept. But it works good enough to deliver *self updates* for this app. And it succesfully planned my upcoming vacation

## Shoulders of giants we're standing on

- [ghostty](https://github.com/ghostty-org/ghostty) -- the best tty
- [ghostty-android](https://github.com/tapthaker/ghostty-android) author [@tapthaker](https://github.com/tapthaker), who did a lot of heavy lifting running it on an alien platform with an alien renderer
- [claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android) for the inspiration with shimming glibc with bionic and in general showing me that it is possible
- [termux](https://github.com/termux/termux-packages) for the userland, and its wonderful community who provides precompiled stuff like arm ndk

## Changelog

### v0.1.0

- Barebones claude-code harness works🎉
- Agents can show you diffs and files
- Supports Projects View (left side, projects/chat list) and Files View (right side, files in selected project)
  - Supports opening new dirs as projects
  - Supports adding files to projects
  - Files View are in sync with the current project in almost a hundret percent of the time
- Code highlighting
- Code selection in writeups and code files
- Ability to discard selection in writeups and code files (trust me, it deserves its own line in changelog)
- Screen rotation or low batterry spawns stray background agents no more
- Support rich map widgets in markdown writeups
- Agents can ping you by sending a notification
- Agents can send arbitrary intents
- Clickable links
- Lots of changes to get the stuff above working