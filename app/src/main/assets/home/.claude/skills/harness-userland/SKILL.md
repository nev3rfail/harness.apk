---
name: harness-userland
description: Use when installing packages, running scripts, or looking for headers, libraries or binaries on this device -- anything where a path from documentation or memory might be wrong, an `apt`/`pkg` install is involved, a `#!/usr/bin/env` shebang is in question, or a program fails to start with `Permission denied`.
---

# The userland this device runs

The shell is bash from a Termux userland relocated into an app's private
directory. Ordinary tools behave normally. What is different is where everything
lives and what may be written.

## The prefix

Read it, do not type it:

```sh
echo $PREFIX          # root/usr inside this channel's data directory
```

Nothing is at `/usr`, `/bin` or `/etc`, and `/` is not writable. Headers are at
`$PREFIX/include`, libraries at `$PREFIX/lib`, binaries at `$PREFIX/bin`. A path
copied out of documentation is wrong here unless it starts with `$PREFIX` or the
app's own directory.

Writable: this channel's data directory, `$TMPDIR` for scratch, and shared
storage once the operator has granted the app all-files access in Settings.
Everything else, including `/tmp`, is not.

## Installing

`apt install <package>` and `pkg install <package>` both work and reach
`termux.net`, the repository of Termux's Google Play build, which carries a full
toolchain -- compilers, a JDK, Android build tools, `git`, `jq`, `python`.
`x11-repo` and `tur-repo` install from it and add those two repositories.

Every archive is rewritten on the way in, because a Termux `.deb` unpacks to an
absolute path this app cannot write and a shim standing in for `dpkg` re-roots
it. So an install costs roughly twice the usual time and prints the shim's
output. Install what you need; do not install what you merely might need --
storage here is shared with the operator's photos.

## How a program starts

Android refuses to execute a file in the app's data directory and allows mapping
one. So every program the agent or a shell starts goes through a loader: a
Termux program through `/system/bin/linker64`, a musl program such as the agent
through the musl loader in the app's native library directory. An exec router
does the choosing. It is `libtermuxexec.so`, preloaded into every bionic process,
and the same code is compiled into the agent's own libc, so it is in front of the
agent's tools, its shell and everything they start, `env -i` included.

The router's path changes on every update. Name it by the variable, never by the
path:

```sh
echo $HARNESS_TERMUX_EXEC     # the router; its directory is the native library directory
```

What follows from it:

- **Shebangs resolve.** `#!/usr/bin/env python3`, `#!/bin/sh` and any `/bin/x`
  or `/usr/bin/x` become `$PREFIX/bin/x` when that file exists.
- **`argv[0]` is the path.** A bionic program started through `linker64` sees its
  path as `argv[0]`, so `exec -a` and a login shell's `-bash` are not kept. A
  musl program keeps its `argv[0]`.
- **Static binaries cannot run.** A statically linked ELF in the data directory
  has no loader to run it and fails with `Permission denied`. That covers
  Termux's `zig` package, the `termux-ndk` toolchain and static release tarballs.
  `termux.net` carries no `zig`.
- **Go programs start other programs.** Go from `termux.net` routes its own
  execs through `linker64`. It cannot start a musl program, since `linker64`
  cannot run one.

## A userland from the F-Droid repository

An install first bootstrapped from Termux's F-Droid repository keeps that
userland across updates. Its packages work through the router, except Go
programs starting other programs, which fail with `fork/exec …: permission
denied`. `grep -r termux.net $PREFIX/etc/apt/sources.list*` says which one is
installed.

The reset deletes every installed package, so ask the operator first. Remove the
`home` symlink under the rootfs before the rootfs itself, so nothing reaches
through it into the app home, then restart the app, which bootstraps again:

```sh
rm "$HARNESS_ROOTFS/home"       # the symlink only
rm -rf "$HARNESS_ROOTFS"
```

## Quick reference

| Question | Answer |
|---|---|
| Where is a header? | `$PREFIX/include` |
| Where do packages install? | under `$PREFIX`, never `/usr` |
| Can I write outside the app directory? | Only to shared storage, once the operator grants all-files access. `$TMPDIR` for scratch |
| Is there a package index? | Yes, `termux.net`, live and complete |
| Why is this install slow? | every `.deb` is re-rooted on the way in |
| `/usr/bin/env` shebang? | resolves to `$PREFIX/bin/env` |
| A downloaded binary says `Permission denied`? | it is static; nothing can run it here |
| `sudo`, a service manager, a port below 1024? | none of them exist |

## Common mistakes

| Mistake | What is true |
|---|---|
| Assuming `$PREFIX` is `files/usr` or `/usr` | It is `root/usr` inside the channel's data directory. Ask the shell |
| Concluding packages cannot be installed | `apt` works; the repository is complete |
| Typing the router's path into `LD_PRELOAD` | The path changes on every update. Use `$HARNESS_TERMUX_EXEC` |
| Relying on `exec -a` or `$0` being a chosen name | A bionic program sees its path as `argv[0]` |
| Writing scratch files to `/tmp` | Not writable. Use `$TMPDIR` |
| Installing a whole language runtime for a one-line job | `awk`, `sed` and `jq` are already here or one small install away |
