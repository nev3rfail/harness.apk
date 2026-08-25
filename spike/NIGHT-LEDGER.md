# Night ledger

Decisions taken without asking, and why. Newest last.

## A stable channel is a second app, and a second prefix

`apk.harness.stable` is a build type rather than a product flavour: one block in
`app/build.gradle.kts`, no new source sets, no flavour dimension to thread
through the library module.

The consequence that matters is not the Gradle change. A different application
id is a different data directory, and the musl loader has its prefix compiled
in -- that is the trap the handoff already records. So the stable channel needs
its own staged agent built with `--prefix /data/data/apk.harness.stable/files/claude`,
its own credentials, and its own trust answer. Staging built for one channel
resolves no names under the other, and the failure looks like a network problem
rather than a packaging one.

Both channels can be installed at once, which is the point: the stable one keeps
working while the other is being broken.

## Three investigations run in parallel

Termux reuse, our own toolbox, and the workspace design are independent -- the
first two are prefix work that touches no app code, the third is app code that
touches no prefix. They are running as separate agents writing to `spike/`.

## The stable channel is on the phone and verified without a screen

`apk.harness.stable` is installed, staged with its own arm64 agent, and running.
The phone's display would not wake -- dozing, and it ignores `KEYCODE_WAKEUP` --
so it was verified from the outside instead, which turned out to be stronger
evidence than a screenshot:

- Two `claude` processes under two uids, the stable one exec'd from
  `/data/user/0/apk.harness.stable/files/claude/claude --ide`.
- Its own IDE lockfile, its own `.mcp.json`, its own resolver file.
- `.claude.json` carries `oauthAccount` and the migration flags, which are only
  written after a successful call to the API. That is the proof the prefix is
  right: a loader built for the wrong prefix resolves no names, and the account
  would never have been fetched.

What is not verified is what it looks like on screen. That is the same renderer
and the same Kotlin as the channel already confirmed on this phone, and the only
thing untested is a build id, so the risk is small and named rather than hidden.

The dev channel's agent was force-stopped: two foreground services holding wake
locks overnight is a battery bill for nothing.

## Traps paid for again

`adb push` from git-bash needs `MSYS2_ARG_CONV_EXCL='*'` for the device path AND
a Windows-shaped local path, because turning path conversion off turns it off
for both sides. The handoff records half of this; this is the other half.

The phone roams between hotspots. `spike/phone.sh` finds it: known addresses
first, then a `/dev/tcp` sweep of the subnets, and only network serials count so
the emulator is never mistaken for it.

## A sandbox probe, because run-as proves nothing

The Termux investigation verified relocation on device but only through
`run-as`, which is a different SELinux domain and a different seccomp filter
from the app. That gap is the whole question: if a relocated bootstrap cannot
exec inside `untrusted_app`, the direction is dead.

So `applicationId` is now overridable from the command line, and a throwaway
build under `apk.hrness` is installed on the emulator:

    :app:assembleDebug -PskipNativeBuild "-PharnessAppId=apk.hrness"

Ten characters is not arbitrary. Relocating a bootstrap means substituting
`/data/data/com.termux` -- twenty-one bytes -- inside stripped binaries, where
the replacement cannot change length. `/data/data/` plus a ten-character id is
exactly twenty-one. `apk.harness` is eleven, one over, and that is a naming
decision belonging to the morning rather than to the night; `apk.hrness` is a
probe, not a proposal.

With no agent staged, the app spawns a plain shell in its own sandbox, and that
shell reports `context=u:r:untrusted_app_27`. It can be driven with
`input text` and answers through files, so it needs no screen:

    adb shell "input text 'id%s>probe.txt'"    # quote, or the device shell eats the >
    adb shell input keyevent 66
    adb shell "run-as apk.hrness cat files/probe.txt"

That is the rig the relocated bootstrap gets tested in.

## The Termux bootstrap runs in the app sandbox

The probe answered, and it answered well. Inside `untrusted_app_27`, from the
app's own shell:

- `env -i $PREFIX/bin/bash -c 'echo alive; uname -m'` prints `alive` and
  `x86_64`. bash 5.3.15, with an empty environment: libraries come from the
  rewritten `DT_RUNPATH` and bash's fallback `PATH` was rewritten too, so
  `Agent.kt` would have to reproduce nothing.
- All five `libtermux-exec*.so` variants redirect `#!/bin/sh` into the prefix.
- `curl` to Termux's repo returns **HTTP 200** and `apt update` fetches 582 kB.

That last one matters more than it looks. Termux's binaries are Bionic-linked,
so they resolve names through netd like any Android app. The whole compiled-in
resolver apparatus this project needs for musl -- the patched musl sources, the
`etc/resolv.conf`, the c-ares preload -- is not needed on this path at all.

`apt install git` fails as expected: apt fetches all ten packages and dpkg
refuses every one, because a Termux `.deb` carries payload paths rooted at
`./data/data/com.termux` and cannot create that directory. Whether the repack
that fixes this works in the sandbox, and whether it is a wrapper or a real
package manager, is the last open question and is being answered.

The static evidence on syscalls is also good: 316 ELF files in the relocated
tree contain no raw syscall sites, against 490 in this project's own musl loader.
Nothing in the bootstrap needs the shim that x86_64 needs today.

## The panel, and the hole it found

Three reviewers, independently briefed, all recommended the toolbox's cheap half
over the Termux bootstrap, and all three said their answer was unchanged if the
rename is refused. Unanimity is the verdict; the panel was not re-run.

All three named the same missing evidence -- the toolbox had never executed
inside the app's own domain and seccomp filter -- so it was tested. It half-fails
on x86_64: busybox and git die with SIGSYS unless run beneath the syscall shim,
busybox's `sh` applet dies even under it, and bash spawning busybox produces
nothing. Written up in `MORNING.md`.

That result is why the panel was worth running. Both spikes were honest and
thorough, and neither had gone near the one test that turns a two-minute build
into an open question.

## A correction to the correction

The first report of the toolbox's in-sandbox behaviour said busybox worked under
the shim and only its `sh` applet failed. That was reading too much into one
command. Running the applets one by one shows **every** busybox applet dying with
SIGSYS, with and without the shim; only `--list` works. `MORNING.md` carries the
corrected table.

The three binaries fail three different ways -- bash fine everywhere, git dead
without the shim and healthy under it, busybox dead either way -- which points at
how each was built rather than at the sandbox.

## Two negative results worth keeping

Writing to `/proc/<pid>/fd/<n>` where the link points at `/dev/ptmx` does not
inject input into a running app's terminal: opening that link allocates a *new*
pty rather than reattaching to the app's. So there is no way to drive the
terminal without a screen, and a sleeping phone stays untestable at the UI.

The arm64 toolbox binaries are staged at `files/tb` under the dev channel on the
phone -- the prefix they were built for -- so the outstanding in-app test is one
command once the display wakes.

## The userland is hand-staged, and that is a debt

A relocated Termux tree now lives at `<dataDir>/root`, and the agent's shell is
its bash. Nothing in the app put it there: it was unpacked from a tar by hand,
and all the app knows is to look for `root/usr/bin/bash` and write a wrapper if
it finds one. That is deliberate -- the shell wrapper was the increment -- but
it is not a state to leave.

Staging it properly owes the same work `stage-claude.sh` does for the agent, and
for the same reason: the prefix is compiled in, so each channel needs its own
tree. Per ABI, per channel: fetch the bootstrap, rewrite every occurrence of the
prefix byte for byte, repoint the twenty absolute symlinks, rewrite dpkg's file
manifests as text where length does not bind, unpack into `root/usr`, create the
cache directory the tree was renamed to expect, and install the repack as apt's
`Dir::Bin::dpkg`. Ninety-three megabytes, so it is staged on the device like the
agent, not carried in the APK.

Two hazards found while proving the layout, both cheap to forget:

**Absolute symlinks do not announce themselves.** Twenty of them, the apt
keyrings among them. Leave them behind and apt reports `NO_PUBKEY … The
repository is not signed`, which reads as a trust problem and invites
`--allow-unauthenticated` instead of a fix.

**The agent updates itself, into a binary that cannot run here.** Both channels
carry `files/.local/share/claude/versions` with a stock build whose interpreter
is `/lib/ld-musl-<arch>.so.1`, a path Android does not have; `.local/bin/claude`
points at it. Nothing execs it today, because the app names the staged binary
directly, so it is only disk -- 382 MB, re-downloaded as releases land. It is
also a brick waiting for anything that ever follows that symlink. The agent's
environment is where to turn the updater off.

## The channels swapped names, and the reason is arithmetic

`apk.harness` is now the channel that has to keep working and `dev.harness` is
the one that iterates. The plain name went to stable because it is the channel a
userland has to fit under: `/data/data/apk.harness.stable` is 29 of the 31 bytes
a relocated Termux prefix may occupy, leaving four -- not enough for a rootfs
directory and a `usr` beneath it. Under that id stable could not have a shell at
all.

Both channels were reinstalled rather than upgraded, since an application id
cannot change in place. What that costs is the agent's login, and
`spike/salvage-auth.sh` is what makes it survive: the home streams out through
`run-as`, comes back after the install, and the paths the agent keyed its trust
and MCP records by are rewritten as text. The restore was proven against a live
channel before anything was uninstalled, which is the only moment when a broken
restore is free.

Still owed here, and named as the next piece of work: the agent's updater. It
downloads a build this device cannot execute, and the fix belongs in the
environment the app hands the agent rather than in a file the agent rewrites.
