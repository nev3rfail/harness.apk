---
name: harness-userland
description: Use when installing packages, running scripts, or looking for headers, libraries or binaries on this device -- anything where a path from documentation or memory might be wrong, an `apt`/`pkg` install is involved, a `#!/usr/bin/env` shebang is in question, a static, glibc or Go binary or `proot` is involved, or a program fails to start with `Permission denied`.
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
through the musl loader in the app's native library directory, and any other
native program -- static, glibc, or built by a stock Go -- under proot, which the
app ships as `libproot.so` in that directory. An exec router does the choosing.
It is `libtermuxexec.so`, preloaded into every bionic process, and the same code
is compiled into the agent's own libc, so it is in front of the agent's tools,
its shell and everything they start, `env -i` included.

The router's path changes on every update. Name it by the variable, never by the
path:

```sh
echo $HARNESS_TERMUX_EXEC     # the router; its directory is the native library directory
```

What follows from it:

- **Shebangs resolve.** `#!/usr/bin/env python3`, `#!/bin/sh` and any `/bin/x`
  or `/usr/bin/x` become `$PREFIX/bin/x` when that file exists.
- **`argv[0]` is the path.** A bionic program started through `linker64`, and a
  program the router starts under proot, sees its path as `argv[0]`, so `exec -a`
  and a login shell's `-bash` are not kept. A musl program keeps its `argv[0]`.
- **Go programs start other programs.** Go from `termux.net` routes its own
  execs through `linker64`, so outside proot it cannot start a musl, static or
  glibc program: `linker64` runs none of them. Under proot, and for a stock Go
  binary, it can.

## Programs under proot

A static binary (Termux's `zig` package, the `termux-ndk` toolchain, a static
release tarball), a glibc program, and a stock-Go binary each run as

```
libproot.so --exec-bin $PREFIX/bin <bindings> <program> <args>
```

Inside that proot:

- the program's `/proc/self/exe` is its own path, and every program it starts
  runs under the same proot, bionic and musl ones included;
- `/bin/x` and `/usr/bin/x` become `$PREFIX/bin/x` when that file exists;
- each file in `$HARNESS_ETC`, `resolv.conf` among them, is at `/etc/<name>`,
  `$PREFIX/etc/tls/cert.pem` at `/etc/ssl/certs/ca-certificates.crt`, the musl
  loader at `/lib/ld-musl-<arch>.so.1`, and, once glibc is installed, its `ld.so`
  at `/lib/ld-linux-aarch64.so.1` (x86_64: `/lib64/ld-linux-x86-64.so.2`). DNS,
  TLS and glibc release downloads then work as on a Linux host.

`proot` typed by name is the same packaged `libproot.so`, with your arguments
unchanged, so `proot-distro` works. `$PREFIX/bin/proot` is a link to it unless
the `proot` package is installed, and either way the router runs the packaged one.

What to expect:

- **Cost.** A proot start is about 0.25 s. Inside one, a bionic exec costs about
  105 ms against 24 ms outside, so run a loop of short bionic commands outside it.
- **The caller waits for the program only.** A daemon the program starts keeps
  running after the caller returns, under a second `libproot.so` process, the
  tracer, which exits with the last process it traces.
- **Status.** The caller gets the program's exit status or its death signal.
  Any core is the program's own, written under its own `RLIMIT_CORE`, and the
  status carries no core-dumped flag.
- **Signals.** `kill <pid>` of the proot the caller started reaches the program.
  `kill -STOP <pid>` stops only that proot process, and `kill -CHLD <pid>` ends at
  the tracer; Ctrl-Z and `kill -STOP -<pgid>` stop the program. A signal sent to
  the process group from outside it, such as `kill -INT %1`, reaches the program
  twice, and `timeout` up to three times.
- **Debuggers.** `strace -f`, `gdb` and `lldb` cannot trace a program under
  proot: proot is its tracer, a debugger that starts it makes proot's own attach
  fail, and proot reports that as an error.
- **`/proc/<pid>/exe` read from outside proot** names `libproot-loader.so`.
- **NDK r29 `-static-pie` output** crashes at start on any Linux kernel, with or
  without proot. zig and musl static-pie programs run.
- **On x86_64** the agent's tree runs under the app's syscall shim, which
  releases a process when it execs proot. The shim answers only the calls
  Android's policy traps; a `SIGSYS` from a program's own seccomp filter reaches
  the program.

## glibc programs

The `glibc-repo` packages install under `$PREFIX/glibc` and run under proot, so
`grun` is not needed. Their repository is signed by the `termux-autobuilds` key,
which apt trusts for that repository alone. Ask the operator first, then:

```sh
cd $TMPDIR
deb=$(curl -fsS --max-time 60 https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages \
  | awk '/^Package: termux-keyring$/ {p=1} p && /^Filename:/ {print $2; exit}')
curl -fsS --max-time 60 -o termux-keyring.deb "https://packages.termux.dev/apt/termux-main/$deb"
dpkg-deb --fsys-tarfile termux-keyring.deb \
  | tar -xO ./data/data/com.termux/files/usr/share/termux-keyring/termux-autobuilds.gpg \
  > termux-autobuilds.gpg
rm termux-keyring.deb
gpg --show-keys --with-colons termux-autobuilds.gpg | grep '^fpr'
```

The last line must print `fpr:::::::::CC72CF8BA7DBFA0182877D045A897D96E57CF20C:`;
any other fingerprint means the key is not Termux's, so stop there. `gpg` is
`pkg install gnupg`. Then:

```sh
mkdir -p $PREFIX/etc/apt/keyrings
mv termux-autobuilds.gpg $PREFIX/etc/apt/keyrings/
echo "deb [signed-by=$PREFIX/etc/apt/keyrings/termux-autobuilds.gpg] https://packages-cf.termux.dev/apt/termux-glibc/ glibc stable" \
  > $PREFIX/etc/apt/sources.list.d/glibc.list
apt update
apt install python-glibc
$PREFIX/glibc/bin/python3 --version
```

The key goes in `keyrings`, not `trusted.gpg.d`, so it vouches for no other
source. The `glibc-repo` package would write the same line without `signed-by`,
so it stays uninstalled, and so does `glibc-runner`.

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
| A static or glibc download? | runs under proot, which the router starts for it |
| A file says `Permission denied`? | its exec bit is off (`chmod +x` it), or it is no native program: another machine's ELF, an object file, or no ELF at all |
| A program is slow to start? | under proot, 0.25 s per start and about 105 ms per bionic exec inside it |
| `sudo`, a service manager, a port below 1024? | none of them exist |

## Common mistakes

| Mistake | What is true |
|---|---|
| Assuming `$PREFIX` is `files/usr` or `/usr` | It is `root/usr` inside the channel's data directory. Ask the shell |
| Concluding packages cannot be installed | `apt` works; the repository is complete |
| Typing the router's path into `LD_PRELOAD` | The path changes on every update. Use `$HARNESS_TERMUX_EXEC` |
| Relying on `exec -a` or `$0` being a chosen name | A bionic program, and one under proot, sees its path as `argv[0]` |
| Fetching `grun` or `glibc-runner` for a glibc program | The router runs it under proot; install the `-glibc` packages only |
| `kill -STOP` of a proot'd program's pid to pause it | That stops the proot process alone. Ctrl-Z, or `kill -STOP -<pgid>` |
| Writing scratch files to `/tmp` | Not writable. Use `$TMPDIR` |
| Installing a whole language runtime for a one-line job | `awk`, `sed` and `jq` are already here or one small install away |
