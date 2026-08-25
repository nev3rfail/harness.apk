# Reusing the Termux distribution

The question is whether the harness can carry a Linux userland by standing on
Termux's packaging work instead of building a package set of its own, and what
shape that reuse can take without the harness becoming a Termux add-on.

Everything below was measured against `bootstrap-2026.08.23-r1+apt.android-7`,
on the x86_64 emulator and on the arm64 phone.

    curl -sSL .../bootstrap-aarch64.zip     32672724 bytes
    sha256 f902017cf09c84189732b6174b56d69b9890468f4fa7394fc1354b573153688e
    curl -sSL .../bootstrap-x86_64.zip      32584674 bytes
    sha256 5f7c54e860df1ef5146b8475dc90e69af666da6588ca1fd47be8f7036b07e8cb

## What a bootstrap archive is

The zip is the contents of `$PREFIX` -- `bin etc include lib libexec share tmp
var` -- plus a `SYMLINKS.txt` at the root, because a zip carries no symlinks.
3766 entries, 93 MB extracted, of which `share/{man,doc,info,misc}` is 33 MB.
`SYMLINKS.txt` holds 1177 links in `target<-linkpath` form; 20 of those targets
are absolute paths under the Termux prefix, the rest relative.

The binaries are ordinary Android executables. They use the system linker and
the system libc, and find their own libraries through `DT_RUNPATH`:

    $ file bin/bash
    ELF 64-bit LSB shared object, ARM aarch64, ... interpreter /system/bin/linker64, stripped

    $ readelf -d bin/bash
     0x000000000000001d (RUNPATH)   Library runpath: [/data/data/com.termux/files/usr/lib]
     0x0000000000000001 (NEEDED)    Shared library: [libandroid-support.so]
     0x0000000000000001 (NEEDED)    Shared library: [libreadline.so.8]
     0x0000000000000001 (NEEDED)    Shared library: [libiconv.so]
     0x0000000000000001 (NEEDED)    Shared library: [libdl.so]
     0x0000000000000001 (NEEDED)    Shared library: [libc.so]

There is no private loader. `libc.so` is bionic, out of `/system/lib64`. Two
consequences matter to the harness:

- Names resolve through bionic, which means through netd. Nothing in a Termux
  userland needs the resolver file the musl loader is built to read.
- The syscalls are the ones bionic makes, which is exactly the app seccomp
  allowlist. The `syscall-shim` translation the musl agent needs on x86_64 has
  nothing to do on Termux binaries.

The prefix is not only in `DT_RUNPATH`. 615 of the 3766 files contain the
literal string `/data/data/com.termux`:

    $ grep -rla '/data/data/com.termux' . | wc -l
    615
    # classified by file(1): 338 ELF, 276 text, 1 SYMLINKS.txt

Of the 338 ELF files, 206 carry it only as the runpath; 132 also carry it in
read-only data -- compiled-in defaults such as
`/data/data/com.termux/files/usr/bin/bash` (59 files), `.../files/home` (58),
`.../files/usr/tmp/` (42), `.../files/usr/bin/sh` (26), `.../etc/hosts`,
`.../etc/tls/cert.pem`, `.../var/lib/dpkg`. Every script in `bin/` carries an
absolute shebang:

    43  #!/data/data/com.termux/files/usr/bin/sh
    33  #!/data/data/com.termux/files/usr/bin/bash
     3  #!/data/data/com.termux/files/usr/bin/perl

So `patchelf` alone does not relocate a bootstrap. It fixes 206 of 615 files.

## Relocating a bootstrap

A whole-tree byte substitution does relocate it, and the substitution has to be
size-preserving. Shortening a string in `.rodata` would shift every byte after
it, and a stripped binary carries nothing to fix the addresses that point
there. Growing one is impossible. So the replacement path must be no longer
than the path it replaces.

All 615 files share the head `/data/data/com.termux`, 21 bytes. Substituting
only that head puts the whole budget on the applicationId: `/data/data/` is 11,
leaving 10 characters, which `com.termux` uses exactly and `apk.harness`
exceeds by one.

**The budget belongs to the prefix, not to the applicationId.** Almost every
occurrence is `/data/data/com.termux/files/…`, 31 bytes through the component
below the data directory, and that component is this app's to name. A byte
spent on a longer applicationId is recovered by naming it in four characters:

    /data/data/com.termux/files/usr   31  ->  /data/data/apk.harness/root/usr   31
    /data/data/com.termux/cache/apt   31  ->  /data/data/apk.harness/cach/apt   31

Measured over the tree: 6979 occurrences are `files/…`, 9 are `cache/…`
(`libapt-pkg.so`, `bin/pkg`, `bin/termux-reset`), 83 are dpkg's own file
manifests under `var/lib/dpkg/info`, which are text and take any length, and 2
are a `foo` fixture in test binaries. The remaining 15 are the bare data
directory, which has nothing below it to give the byte back. All 15 are in
`termux-exec` and `termux-core`, whose string tables also carry
`TERMUX_APP__DATA_DIR`, `TERMUX__ROOTFS` and `TERMUX__PREFIX`: they are
compiled defaults, and the environment overrides them.

So an eleven-character applicationId costs no padding at all, and the
textual-comparison risk padding carries does not arise. Verified end to end
under uid `u0_a210` at `/data/data/apk.harness/root/usr`: bash 5.3.15 from an
empty environment, a shebang script whose interpreter is the compiled prefix,
`apt update` against the signed repository, `apt install -y git` through the
repack, `git version 2.55.0`, `dpkg --verify git` clean, and
`git clone --depth 1` of `Hello-World` landing `7fd1a60`.

**Absolute symlinks have to be repointed, and only they are free.** Twenty of
the tree's links are absolute -- the apt keyrings in
`etc/apt/trusted.gpg.d` among them -- and a link target is a string in its own
inode, so it is recreated rather than edited and no length applies. Leaving
them behind does not read as a broken link: apt reports
`NO_PUBKEY … The repository is not signed`, which invites a workaround instead
of a diagnosis. `spike/relocate.py` does both passes.

A shorter replacement can be padded to length with extra slashes, which the
kernel and the linker collapse. Both cases were tested.

Exact length, no padding -- `/data/data/com.termux` becomes
`/data/local/tmp/tx.hh`, the tree extracted at
`/data/local/tmp/tx.hh/files/usr`, symlinks recreated from `SYMLINKS.txt`, on
the x86_64 emulator:

    $ $P/bin/bash --version
    GNU bash, version 5.3.15(1)-release (x86_64-pc-linux-android)
    $ $P/bin/apt --version
    apt 2.8.1 (x86_64)
    $ $P/bin/dpkg -l | tail -1
    ii  zstd  1.5.7-1  x86_64  Zstandard compression

Padded -- `/data/data/com.termux` becomes `/data/local/tmp/th///`, which makes
the runpath `[/data/local/tmp/th////files/usr/lib]` and the shebangs
`#!/data/local/tmp/th////files/usr/bin/sh`. Same results: `bash`, a shebang
script (`bzgrep`), and `apt` all run. The same padded tree built for aarch64
and run on the phone:

    $ getprop ro.product.cpu.abi
    arm64-v8a
    $ $P/bin/bash --version
    GNU bash, version 5.3.15(1)-release (aarch64-unknown-linux-android)

Padding is a residual risk rather than a free lunch: any code that compares a
path against the prefix textually rather than canonically will see
`/x///files/usr` where it expects `/x/files/usr`. Naming the rootfs directory
to length avoids padding altogether, so the risk need not be taken.

`apt update` works unchanged, since the mirror list is prefix-independent:

    $ $P/bin/apt update
    Get:1 https://packages-cf.termux.dev/apt/termux-main stable InRelease [14.0 kB]
    Fetched 582 kB in 2s (301 kB/s)

The apt cache lives outside the prefix, in the app data directory, and has to
be created:

    E: Archives directory /data/local/tmp/tx.hh/cache/apt/archives/partial is missing.

## Packages from the Termux repository

This is where a plain relocation stops. A Termux `.deb` is not
prefix-relative; it is rooted at the absolute Termux path:

    $ dpkg-deb -c git_2.55.0_x86%5f64.deb | head
    drwxr-xr-x ./
    drwxr-xr-x ./data/
    drwxr-xr-x ./data/data/
    drwxr-xr-x ./data/data/com.termux/
    drwxr-xr-x ./data/data/com.termux/files/
    drwx------ ./data/data/com.termux/files/usr/
    drwx------ ./data/data/com.termux/files/usr/bin/
    -rwx------ ./data/data/com.termux/files/usr/bin/git

So `apt install` into a relocated prefix fails on every package:

    Unpacking git (2.55.0) ...
    dpkg: error processing archive .../1-git_2.55.0_x86%5f64.deb (--unpack):
     error creating directory './data/data/com.termux': Permission denied

The fix is the same substitution applied to each archive before installing it,
and the tools to do it are in the bootstrap. Unpack, move the payload to the
new path, run the substitution over every file including the maintainer
scripts, repack, install. That was tested on the emulator over `git` and its
nine dependencies:

    for deb in "$ARCH"/*.deb; do
      dpkg-deb -R "$deb" "$d"
      mkdir -p "$d/data/local/tmp/th/files"
      mv "$d/data/data/com.termux/files/usr" "$d/data/local/tmp/th/files/usr"
      rm -rf "$d/data/data"
      find "$d" -type f -print0 | xargs -0 sed -i "s|$OLD|$NEW|g"
      dpkg-deb -b "$d" "$OUT/$(basename "$deb")"
    done
    dpkg -i "$OUT"/*.deb

    $ git --version
    git version 2.55.0
    $ git init -q gt && cd gt && echo hi > a && git add a && git commit -qm x && git log --oneline
    d768fe5 x

Two details the run turned up: `dpkg-deb -b` rejects maintainer scripts whose
mode is outside `0555..0775`, so the repack has to chmod them; and the
`md5sums` control file is left stale, which `dpkg -i` does not check but
`dpkg --verify` would.

Note also that part of the repository is Termux-app-specific: `termux-am`,
`termux-api` and their kin talk to the Termux app over Android IPC and are dead
weight to anyone else.

## termux-exec

`termux-exec` is an `LD_PRELOAD` library that interposes the whole exec family:

    $ readelf --dyn-syms lib/libtermux-exec-direct-ld-preload.so | grep -E 'exec'
    execl execlp execv execvp execle execvpe fexecve execve

It does two jobs.

The first is shebang rewriting. A script written for an ordinary Linux starts
`#!/bin/sh` or `#!/usr/bin/env node`, and neither means what it should on
Android:

    $ ls -l /usr
    ls: cannot access '/usr': No such file or directory
    $ ls -l /bin/sh
    -rwxr-xr-x 1 root shell 351168 /bin/sh          # Android's toybox, not bash

The library carries `/bin/`, `/bin/sh` and `/usr/bin/sh` and redirects them
into `$PREFIX`. Termux's own packaged scripts do not need this -- their
shebangs are already absolute -- but anything the agent writes, any npm package
with `#!/usr/bin/env node`, and any git hook does.

The second is "system linker exec": executing a binary as an argument to
`/system/bin/linker64` rather than directly, which is how Termux survives on
API 29 and later where an app's own data directory loses `execute_no_trans`.
The strings `/system/bin/linker64` and `/system/bin/linker` are in the library.
The harness targets 28 and does not need this, but it is worth knowing that it
exists and answers the objection that made targetSdk 28 necessary -- the
linker-exec trick was rejected because every subprocess would need it too, and
interposing exec is exactly how Termux makes it apply to every subprocess.

The library is prefix-aware at run time. Its shipped defaults are the Termux
paths, but it reads `TERMUX__PREFIX`, `TERMUX__ROOTFS`, `TERMUX_APP__DATA_DIR`
and `TERMUX_EXEC__PROC_SELF_EXE` from the environment. Those defaults are also
the only place in the bootstrap where the bare 21-byte `/data/data/com.termux`
appears -- nine files, all of them `termux-exec` or `termux-core` libraries and
their test binaries.

So an equivalent is needed either way, and the equivalent already exists and
travels in the bootstrap.

## Building termux-packages with our own prefix

Supported, and documented as supported. `scripts/properties.sh` names the
variables that a fork may change:

    # Following is a list of `TERMUX_` variables that are safe to modify when forking.
    # - `TERMUX_APP__PACKAGE_NAME`.
    # - `TERMUX_APP__DATA_DIR`.
    # - `TERMUX__ROOTFS_SUBDIR`.
    # - `TERMUX__ROOTFS` and alternates.
    # - `TERMUX__PREFIX` and alternates.

    TERMUX_APP__PACKAGE_NAME="com.termux"
    TERMUX_APP__DATA_DIR="/data/data/$TERMUX_APP__PACKAGE_NAME"
    TERMUX__ROOTFS="$TERMUX_APP__DATA_DIR/$TERMUX__ROOTFS_SUBDIR"
    TERMUX__PREFIX="$TERMUX__ROOTFS${TERMUX__PREFIX_SUBDIR:+"/$TERMUX__PREFIX_SUBDIR"}"

The limits are generous compared with the byte-rewrite budget:
`TERMUX_APP__DATA_DIR___MAX_LEN=69`, `TERMUX__ROOTFS_DIR___MAX_LEN=86`, and the
package-name guidance is "ideally `<= 21` characters and max `33`".
`/data/data/apk.harness/files/usr` is comfortably inside all of them. The one
firm rule is that with usr-merge enabled -- the default -- `TERMUX__PREFIX`
must be `$TERMUX__ROOTFS/usr` and must not be under `TERMUX__HOME`.

The machinery is a docker image and a per-package build:

    ./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures aarch64 --add openssh

`build-bootstraps.sh` calls `build-package.sh` per package and extracts the
resulting debs into a bootstrap root. With a non-default prefix nothing
prebuilt is usable, so every package and every dependency is compiled. What
that buys is a bootstrap zip with no rewriting anywhere, no length constraint,
and no repack step at install time. What it owes is a build farm, plus either a
repository to host or a habit of rebuilding the zip whenever the package set
changes.

Building with a custom prefix was not attempted here. It is asserted by
upstream, not verified by this spike.

## Two apps

The sandbox boundary is not negotiable. `/data/data/com.termux` is unreadable
to the harness and `/data/data/apk.harness` is unreadable to Termux; nothing
about targetSdk changes that. What can cross:

**Loopback.** Verified across a uid and SELinux domain boundary on the
emulator: a listener opened by `u:r:shell:s0` was read by a client under
`u:r:runas_app:s0` at uid 10210.

    $ echo LOOPBACK_OK | toybox nc -l -p 45678 &
    $ run-as apk.harness toybox nc 127.0.0.1 45678
    LOOPBACK_OK

This is the channel the IDE protocol already uses, and `docs/ide-protocol.md`
records the part that makes it work across apps: `CLAUDE_CODE_SSE_PORT` names
the editor's port directly and skips both the lockfile scan and the pid check.
An agent running inside Termux could therefore attach to the harness as its
editor with no shared filesystem at all.

**Shared storage.** Real for a targetSdk 28 app, and useless as a userland or a
workspace. Emulated storage is `noexec`, refuses symlinks, and ignores mode
bits:

    /dev/fuse on /storage/emulated type fuse (rw,lazytime,nosuid,nodev,noexec,noatime,...)
    $ ln -s foo hxlink
    ln: cannot create symbolic link ...: Permission denied
    $ cp /system/bin/toybox /sdcard/hxtoy; chmod 755 /sdcard/hxtoy; ls -l /sdcard/hxtoy
    -rw-rw---- 1 u0_a192 media_rw 577160 /sdcard/hxtoy
    $ /sdcard/hxtoy echo EXEC_WORKED
    /system/bin/sh: /sdcard/hxtoy: can't execute: Permission denied

A git working tree there loses symlinks and the executable bit, and no binary
placed there can run.

**Termux's RUN_COMMAND intent.** A third-party app must declare
`com.termux.permission.RUN_COMMAND`, and the user must set
`allow-external-apps=true` in `~/.termux/termux.properties`. It passes a
command, arguments, stdin and a working directory, and returns stdout, stderr
and an exit code through a pending intent, truncated to 100 KB combined. It is
fire-and-collect: no pty, no streaming, no interactivity.

So a two-app split has exactly two shapes.

*Agent in Termux, harness as the editor.* Termux carries everything including
the agent; the harness binds its MCP WebSocket on loopback, hands the agent
`CLAUDE_CODE_SSE_PORT`, and gets a pty by running an ssh server in Termux and
connecting to it over loopback. Coherent, and it makes the harness a Termux
front-end in everything but name: the user installs Termux, installs a package
set, installs and configures sshd, and every one of those steps is a place the
product can fail before it starts.

*Agent in the harness, shell tool in Termux.* The agent's file tools and its
Bash tool then see different filesystems. The only place both can reach is
shared storage, which cannot hold a usable git checkout. This one does not
work.

## Options

**A. Relocated bootstrap in the app's own data directory, with a repack step
for packages.** Verified end to end on both architectures, including
`apt install` of git after rewriting the archives. One app, no Termux
dependency at run time, the full Termux repository reachable. Costs a prefix of
at most 31 bytes, which any reasonable applicationId affords, and ownership of
roughly fifty lines of repack that have to keep working as Termux's packaging
evolves.

**B. Own bootstrap built from termux-packages with our prefix.** No rewriting,
no length constraint, no repack. Costs a docker build farm, a full rebuild of
every package wanted, and either a hosted repository or a rebuilt zip per
change. Upstream sanctions it; this spike did not run it.

**C. Two apps, agent inside Termux.** Zero packaging work. Costs the product:
Termux install, package install, sshd setup, and a support surface shaped like
somebody else's app.

**D. Nothing.** The agent keeps Android's toybox shell and reaches for MCP
tools instead of `git` and `curl`. The cheapest option, and the baseline
against which the others have to justify 93 MB.

## Recommendation

**A.** The rewrite is proven, on both architectures and on the real phone, up
to and including installing git out of Termux's own repository and making a
commit with it. It keeps the harness a single app that owes Termux nothing at
run time, which is the shape asked for. B is the same result for an order of
magnitude more machinery, and is the thing to reach for if the repack step
turns out to fight back. C trades the packaging problem for a worse product
problem.

What A demands up front is a layout, not a name. The prefix is what has to fit
in 31 bytes, so an eleven-character applicationId buys its byte back by naming
the rootfs directory in four: `$ROOTFS` is the app's `root`, `$PREFIX` its
`root/usr`, `$HOME` its `root/home`, and apt's cache its `cach`. That is a
byte-for-byte swap with no padding anywhere, which is what the
textual-comparison risk was about.

Ship the bootstrap the way the agent binary is shipped -- downloaded and staged
on device, not in the APK -- and trim `share/{man,doc,info,misc}` to cut 33 MB
of the 93.

## Not verified

- None of it was run inside the app sandbox. Exec from the app's data directory
  at targetSdk 28 is established by the agent binary, and Termux itself is the
  existence proof that bionic binaries load their libraries from an app data
  directory through the system linker, but the harness has not done it.
- That Termux binaries need no syscall shim under the app's seccomp filter is
  an inference from their being bionic-linked, not a measurement. The runs
  above were in the `shell` domain, whose filter is not the app's.
- The bootstrap's `termux-bootstrap-second-stage.sh`, which runs each package's
  `postinst` after the app extracts the archive, was never run. `apt` and
  `dpkg` worked without it, which says only that it was not needed for those.
- `termux-exec` was not loaded. Whether its `TERMUX__PREFIX` handling behaves
  after relocation is untested.
- Building termux-packages against a custom prefix is claimed by upstream and
  not attempted.
- Loopback was verified between `shell` and `runas_app`, not between two
  ordinary apps. Whether the CLI sends the
  `X-Claude-Code-Ide-Authorization` header when the port comes from
  `CLAUDE_CODE_SSE_PORT` rather than a lockfile is unknown.
- Whether a targetSdk 28 app still receives legacy external storage on
  Android 15 was not tested; it matters only to option C, which is not
  recommended.

## Probe payload: a bootstrap relocated to `/data/data/apk.hrness`

Two tars, built by replacing the 21 bytes `/data/data/com.termux` with the 21
bytes `/data/data/apk.hrness` throughout the tree. Equal length, so this is a
straight byte swap with no slash padding anywhere and none of the
textual-comparison risk padding carries.

    termux-x86_64-relocated.tar   94187520 bytes
    sha256 36d93c6ad5a3ac9bae2597237fea6673304724eab8b20a07745ef059b1add3ef
    termux-arm64-relocated.tar    93050880 bytes
    sha256 6b062cc72e36ef50d324e53c5fdc58ddd0ccfe285ba37272f586f14b57945879

    $ readelf -d bin/bash | grep -i runpath
     0x000000000000001d (RUNPATH)   Library runpath: [/data/data/apk.hrness/files/usr/lib]
    $ head -1 bin/bzgrep
    #!/data/data/apk.hrness/files/usr/bin/sh

The tar root is the contents of `$PREFIX`, so it unpacks into `files/usr`. A
tar carries symlinks, so the 1179 (x86_64) and 1177 (aarch64) links that
`SYMLINKS.txt` describes are real symlinks in the archive and `SYMLINKS.txt`
itself is dropped -- there is no sidecar to process. 93 MB of content, which
lands as 116 MB on the device once several thousand small files are rounded up
to blocks.

### Unpacking

The unpack step needs no exec, so `run-as` is enough for it and modes and
symlinks survive:

    adb push termux-x86_64-relocated.tar /data/local/tmp/termux-x86_64-relocated.tar
    adb shell run-as apk.hrness sh -c \
      'mkdir -p files/usr && tar xf /data/local/tmp/termux-x86_64-relocated.tar -C files/usr'

Verified against the existing package on the emulator:

    UNPACK_OK
    -rwx--x--x 1 u0_a210 u0_a210 841296 files/txprobe/bin/bash
    lrwxrwxrwx 1 u0_a210 u0_a210      9 files/txprobe/bin/ls -> coreutils
    lrwxrwxrwx 1 u0_a210 u0_a210     18 files/txprobe/lib/libreadline.so.8 -> libreadline.so.8.3

The x86_64 tar is already staged at
`/data/local/tmp/termux-x86_64-relocated.tar` on `emulator-5554`.

### Running

From inside the app -- not `run-as`, which is a different SELinux domain and a
different seccomp filter:

    /data/data/apk.hrness/files/usr/bin/bash -c 'echo alive; uname -m'

### The minimum environment is empty

Nothing has to be set. Measured with `env -i`, on the same tree relocated to a
21-byte path under `/data/local/tmp`:

    $ env -i $P/bin/bash -c 'echo alive; uname -m'
    alive
    x86_64
    $ env -i $P/bin/bash -c 'echo $PATH'
    /data/local/tmp/tx.hh/files/usr/bin:.

Two reasons it needs nothing. Libraries come from `DT_RUNPATH`, which the
rewrite already points at the new prefix. And `bash` was compiled with the
Termux prefix as its fallback `PATH`, which the rewrite moves with everything
else -- so even with an empty environment, `bash` finds `uname` in the
relocated `bin`.

What each variable is actually worth to `Agent.kt`:

| Variable | Required? | What it is for |
| --- | --- | --- |
| `LD_LIBRARY_PATH` | no | `DT_RUNPATH` already resolves every library. Setting it only risks overriding that. |
| `PATH` | not for `bash` | `bash`'s compiled-in fallback covers `$PREFIX/bin`. Set it anyway: any process that is not `bash` inherits whatever it is given, and the fallback also appends `.`, which is worth not having. |
| `PREFIX` | no | Nothing in the bootstrap reads it to find itself. It is a convention scripts use, and the agent will expect it. |
| `TERMUX__PREFIX` | no | `termux-exec` reads it, but its compiled-in default was rewritten with everything else and is already correct. |
| `HOME` | no | Nothing needs it to start. The first thing that writes a dotfile does. It must not be `$PREFIX` or under it. |
| `TMPDIR` | no | `$PREFIX/tmp` is compiled in where there is a default. |
| `LD_PRELOAD` | only for shebangs | See below. |

So the probe needs no environment at all, and a real shell wants `PATH`,
`HOME`, `PREFIX` and `LD_PRELOAD` -- four, none of them load-bearing for the
question the probe is asking.

### termux-exec works after relocation

`LD_PRELOAD` alone, no `TERMUX__PREFIX`. The library has to be loaded in the
process that calls `execve`, which is the shell, not the `env` that launched
it:

    $ env -i PATH=$P/bin $P/bin/bash -c "$P/tmp/a.sh"          # #!/bin/sh
    I_AM /system/bin/sh
    $ env -i PATH=$P/bin LD_PRELOAD=$P/lib/libtermux-exec-ld-preload.so \
        $P/bin/bash -c "$P/tmp/a.sh"
    I_AM /data/local/tmp/tx.hh/files/usr/bin/dash

    $ env -i PATH=$P/bin $P/bin/bash -c "$P/tmp/b.sh"          # #!/usr/bin/env sh
    bash: .../b.sh: /usr/bin/env: bad interpreter: No such file or directory
    $ env -i PATH=$P/bin LD_PRELOAD=$P/lib/libtermux-exec-ld-preload.so \
        $P/bin/bash -c "$P/tmp/b.sh"
    I_AM /data/local/tmp/tx.hh/files/usr/bin/dash

    $ ... TERMUX_EXEC__LOG_LEVEL=2 ...
    31130 E termux.exec: <----- execve() intercepted ----->

Without it, `#!/bin/sh` silently gets Android's toybox and `#!/usr/bin/env`
fails outright. This closes the "termux-exec was not loaded" gap above.

### The syscall shim is not needed, and the reasoning holds

Agreed, and it is checkable statically rather than only by argument. The app's
filter blocks 238 of 463 x86_64 syscalls (`tools/blocked-syscalls-x86_64.txt`).
A Bionic-linked binary reaches the kernel only through Bionic's wrappers, which
by construction use the calls Android allowlisted -- but a package could still
emit a raw `syscall` instruction, and that is what would trip the filter.

Disassembling every ELF in `bin/` and `lib/` of the relocated x86_64 tree finds
none:

    scanned 316 ELF files
    syscall instruction sites: 0

The scanner is not silently broken; run against this project's own musl loader
it finds what is there:

    $ objdump -d ld-musl-x86_64.so.1 | grep -c 'syscall$'
    490
    # resolved numbers: 72 (x19), 3 (x19), 14 (x10), 16 (x7), 202, 200, 268 ...

The only kernel entries the bootstrap reaches for by number go through Bionic's
`syscall()` function, three call sites in all, and all three are permitted:

    coreutils      mov $0x13c,%edi ; call <syscall@plt>    316  renameat2  allowed
    libcrypto.so.3 mov $0x145,%edi ; call <syscall@plt>    325  mlock2     allowed
    libcrypto.so.3 mov $0x13e,%edi ; call <syscall@plt>    318  getrandom  allowed

On aarch64 the question does not arise -- the ABI has only the `*at` calls --
and the same scan finds no `svc` sites either.

So no shim, and no cost to name. Two caveats. This covers the bootstrap only:
a package installed later could emit a raw syscall, and the same scan is what
would catch it. And it is static evidence about the binaries, not a measurement
of the app's filter, which is exactly what the in-sandbox probe will settle.

## The repack, inside the app sandbox

Four answers first, all measured under `u:r:untrusted_app_27:s0`, driven
through the app's own shell with `input text` and read back from a file:

- **`git` installs and runs.** `apt install -y git` returns 0, all ten packages
  reach state `ii`, and `git version 2.55.0` answers.
- **It clones over https and commits.** `git clone --depth 1
  https://github.com/octocat/Hello-World.git` lands `7fd1a60`, and a commit on
  top of it is `3743b2a`. No certificate store to set up: the bootstrap's own
  `etc/tls` is what git uses, and Bionic resolves the name through netd.
- **It is interception, not a wrapper.** Nothing has to be remembered at a call
  site; `apt install` is the command.
- **Ten of ten needed rewriting. None resisted.**

The context also settles the questions the earlier sections left open: the same
tree runs from the app's own data directory in the app's own domain and seccomp
filter, `env -i bash -c 'echo alive'` works there with no environment at all,
all five `libtermux-exec*.so` variants redirect `#!/bin/sh`, and `curl` to
`packages.termux.dev` returns 200 -- so the resolver work the musl agent needs
has no counterpart on this path.

### How it intercepts

`spike/repack-deb.sh` stands in for `dpkg`. Apt has a configuration point for
exactly this, so there is no new mechanism to invent:

    cp repack-deb.sh $PREFIX/libexec/repack-deb
    chmod 755 $PREFIX/libexec/repack-deb
    echo 'Dir::Bin::dpkg "'$PREFIX'/libexec/repack-deb";' \
      > $PREFIX/etc/apt/apt.conf.d/99-repack

    $ apt-config dump | grep -i dir::bin::dpkg
    Dir::Bin::dpkg "/data/data/apk.hrness/files/usr/libexec/repack-deb";

Every apt entry point -- `apt install`, `apt upgrade`, `pkg` -- goes through
that one binary, so all of them are covered by one line of configuration. It
sits in `$PREFIX/libexec` because nothing owns that directory: an upgrade of
the `dpkg` package cannot overwrite it, and neither can an upgrade of `apt`
touch an unowned file in `apt.conf.d`.

What it does not cover is a `dpkg -i` typed by hand, which goes straight to the
real binary. That is the one remaining place a person has to know something,
and it can be closed by installing the script as `$PREFIX/bin/dpkg` with the
real one renamed -- the script already looks for `dpkg.real` first so it cannot
exec itself -- at the price of a file the `dpkg` package owns and will
overwrite.

### What apt actually hands dpkg

Not what the obvious reading suggests, and the difference is the whole
implementation:

    --status-fd 14 --no-triggers --unpack --auto-deconfigure
    --recursive /data/data/apk.hrness/files/usr/tmp/apt-dpkg-install-h3gD9o

Apt never names the archives. It fills a directory and passes `--recursive`, so
a shim that only rewrites arguments ending in `.deb` rewrites nothing and the
originals install unchanged. The directory is filled with *links* into apt's
archive cache, so a `find` with `-type f` also comes back empty. Everything
else apt calls dpkg for -- `--print-foreign-architectures`,
`--assert-multi-arch` -- names no archive and is passed straight through.

One more trap, cheap to hit and confusing to read: a script authored on Windows
carries CRLF, the kernel then looks for an interpreter named `/bin/sh\r`, and
apt reports it as `Could not exec dpkg!`.

### What the rewrite had to touch

Every one of the ten had its payload rooted at `./data/data/com.termux`, so
every one needed the path move. 141 of their 1020 payload files also carried
the prefix inside them:

    package                 files   hits  maintainer scripts
    git                       294     46   0
    krb5                      139     55   0
    ldns                      511      6   0
    libdb                       5      2   0
    libedit                     7      2   0
    libexpat                   12      2   0
    libresolv-wrapper           6      3   0
    openssh                    39     20   1
    openssh-sftp-server         3      1   0
    termux-auth                 4      4   0

The one maintainer script is `openssh`'s `postinst`, which is text and is
rewritten with everything else; it ran and produced its usual key-generation
output.

Nothing resisted, and the result is checkable rather than assumed. Across all
1251 installed files, no path survives:

    git: 450 files, 0 still naming com.termux
    krb5: 163 files, 0 still naming com.termux
    ldns: 511 files, 0 still naming com.termux
    ... (all ten, all 0)

    $ dpkg --verify git
    verify exit: 0

`dpkg --verify` passing is not free: the payload changes, so the recorded
`md5sums` have to be recomputed or every file reads as corrupt. The script does
that.

### What it costs

The same ten packages reinstalled, timed in the shell domain, differing only in
how the rewritten archive is packed:

    xz, the repository's own setting   35.15s real   17.78s user
    uncompressed                       18.09s real    1.54s user

Recompression was the entire cost -- for `git` alone, `dpkg-deb -b` with xz was
11.85s against 0.39s uncompressed, while the substitution over its 294 files
took 1.00s and the unpack 0.39s. The rewritten archive is unpacked again
seconds later by the dpkg the script execs and then deleted, so there is
nothing to compress for, and the script passes `-Znone`.

So an install costs roughly twice the wall time of an ordinary one and a
transient copy of each archive in `$TMPDIR`. That is the price of the whole
approach, and it is paid per install rather than per run.

### What this does not cover

- The rewrite is textual. A package that builds its prefix at run time from
  something other than a literal string would pass through untouched and break
  quietly. None of these ten did, and the "still naming com.termux" check is
  what would catch the ones that do.
- The length rule still binds, and the script enforces it rather than
  corrupting anything: an app data directory longer than
  `/data/data/com.termux` is refused with an explanation.
- Only `apt` is intercepted. A package that runs `dpkg` itself from a
  maintainer script would bypass it.
