# Morning

Three things need a decision, and one of them is yours alone. Everything else is
either done or written down.

## Decide

**1. The app's name -- settled, and it was never a product decision.**
Relocating a Termux bootstrap substitutes a path inside stripped binaries, where
the replacement cannot change length. The path is the whole prefix, 31 bytes,
not the 21-byte data directory: the component below the data directory is this
app's to name, so a longer application id is paid for by a shorter one there.

    /data/data/com.termux/files/usr   ->   /data/data/apk.harness/root/usr

`apk.harness` and `dev.harness` both fit with no slash padding, so the
textual-comparison risk that made a ten-character id attractive does not arise.
Verified at that prefix: apt against the signed repository, `apt install -y git`
through the repack, `dpkg --verify` clean, and a clone over https. Detail and
the two traps -- absolute symlinks, and `termux-exec`'s compiled defaults -- are
in `termux-reuse.md`.

**2. Termux bootstrap or our own toolbox.** Three independent reviewers said the
same thing, so this is a recommendation rather than an open question:

> Take the toolbox's cheap half -- busybox, bash, and git without networking --
> and stop there. Do not take the Termux bootstrap. **Unchanged if the rename is
> refused**, which is the point: it is the move that does not depend on a
> decision you have not made, and it forecloses nothing, because five megabytes
> of staged binaries is cheap to throw away.

Their reasoning converged too. What the Termux path really costs is not the
megabytes or the app's name, it is a standing interception point inside
somebody else's package manager -- one that already surprised the investigation
(apt passes dpkg a directory of links and never names an archive) and that fails
silently, on a phone, mid-install, six months from now. This project's scar
tissue is compiled-in paths and things that report success while doing nothing;
that is the same family.

The strongest argument against, which all three raised and none could dismiss:
the Termux path delivers a package manager, and ours delivers a fixed list. The
first request for ripgrep, jq or python is another cross-build each. If the
appetite turns out to be a package *set* rather than shell-and-git, the
arithmetic flips.

One reviewer also killed an idea worth killing before it is proposed: moving the
agent binary into the Termux prefix does not delete the musl apparatus, because
Termux ships no musl loader. Under the Termux path the app maintains two
userlands, not one.

## The toolbox has a hole on x86_64

All three reviewers said the toolbox had never been run inside the app's own
sandbox, and that this was the evidence that would change their minds. It has now
been run there, and it half-fails:

| in `untrusted_app_27` | without the shim | under the shim |
| --- | --- | --- |
| `bash -c` | works, 5.2.37 | works |
| `busybox --list` | -- | works, 394 applets |
| `busybox true`, `echo`, `ls`, `cat`, `sh` | **SIGSYS** | **SIGSYS** |
| `git --version`, `git commit` | **SIGSYS** | works, commits `bc4f2ff` |

The busybox row is the bad one, and it is worse than it first looked. Listing
applets works, so the binary loads and runs; but **every applet dies with SIGSYS,
and the shim does not save it** -- the same failure with and without. The shim's
verbose mode logs nothing before the death, so it is dying on a syscall the shim
does not translate at all, not on one it translates wrongly.

git is the opposite shape: dead without the shim, fine under it, and it made a
real commit. bash needs no help either way. So the three binaries fail in three
different ways, which suggests how each was built matters more than the toolbox
document assumes.

Identifying the offending syscall needs either a shim that logs every call or a
tracer on the device, and both are code changes rather than measurements, so they
were left for daylight.

On arm64 the same three binaries are healthy: `busybox true` exits zero, bash
runs, `git version 2.47.1`. That is `run-as` evidence, which this project has
learned to distrust -- different SELinux domain, different seccomp filter -- so
it is weaker than the x86_64 result, not equal to it.

But there is a structural reason to expect it holds. The aarch64 Linux ABI has
only the `*at` syscalls; the legacy ones the filter blocks do not exist to be
emitted. That is the same fact that means arm64 needs no syscall shim at all.
So the x86_64 failure is very likely an x86_64 artifact, and the phone -- the
actual target -- is very likely fine.

Closing that "very likely" needs one command. The arm64 toolbox is already staged
at `files/tb` in the dev channel, which is the prefix it was built for. Wake the
phone, open `apk.harness`, and from the agent's own Bash tool run
`files/tb/busybox true`. If it exits zero, the emulator is the only casualty.

This does not overturn the recommendation. It does mean the cheap half is not as
cheap as two minutes of build time suggests, and it should be fixed before
anything is built on top of it.

A last detail worth seeing, because it is the project's oldest lesson arriving
on schedule: git ran but complained it could not read
`/data/data/apk.harness/files/claude/etc/gitconfig` while running under
`apk.hrness`. The binary carries the prefix of the channel it was built for.
Every channel needs its own toolbox build, exactly as every channel needs its own
loader.

**3. The workspace design.** `spike/workspace-design.md` is ready to become a
spec. It is well scoped and cuts hard. Two findings in it are worth reading even
if the rest waits: the panel server's auth token would leak into shared storage
the moment projects land there, and the CLI records its own trust and MCP
approvals in `.claude.json`, so seeding them makes switching projects cost no
dialogs at all.

## Done and verified

**The terminal bug is fixed.** It was `ESC[?1049h` -- the CLI switches to the
alternate screen when a menu is answered, and the renderer read the primary
screen unconditionally. Fixed at all twenty-one sites, verified on both devices.

**`apk.harness.stable` is on the phone**, installed, staged with its own arm64
agent, signed in and reaching the API. Both channels can be installed at once.
It was verified without a screen, because the phone's display would not wake;
what is unverified is only how it looks, on code already confirmed on that phone.

## Where our own toolbox stands

Also strong, and cheaper than it looked. busybox 1.36.1 and bash 5.2.37, static,
both architectures, 2.5 MB together and about two minutes to build with no new
host dependencies. git 2.47.1 builds too, and `git clone --depth 1` of git's own
repository completed on the phone.

Two findings correct things asserted earlier in the conversation:

- **A static build does not escape the prefix problem, it bakes it in.** musl
  compiles `/etc/resolv.conf` into the executable, so a static busybox resolves
  no names on Android either. The toolbox has to be built against the same
  patched musl the agent uses. The claim that busybox would be "prefix-free"
  was wrong, and it was checked both ways with strace rather than argued.
- **Android's certificate directory is unusable to OpenSSL 3.** The files are
  named by the pre-1.0 `subject_hash_old`, and OpenSSL 3 looks up `subject_hash`,
  so every clone fails on issuer lookup. Fixable -- concatenate the directory
  into one bundle beside the resolver file the app already writes -- but it is a
  thing the app then owns.

The shape of the cost: busybox and bash answer the entire "the shell is toybox"
complaint for 2.5 MB. git without networking is another 3.2 MB and one `make`.
git over https is a further 6.1 MB, openssl and curl to keep patched, and that
certificate bundle. So the cheap half is very cheap and the expensive half is
exactly where Termux is free.

Worth knowing for later: Alpine packages can be made to run with `patchelf`
alone, no toolchain -- verified for two dozen of them on the phone -- which is a
good answer for anything that is only a packaging problem, like ripgrep or jq.
It fails for git specifically, because Alpine builds curl against c-ares, which
reads the real `/etc/resolv.conf`.

Also a correction to the premise this started from: toybox has 209 applets and
already provides `awk`, `sed`, `diff`, `patch`, `find` and `xargs`. The gap is
bash, git, URL fetching and applet depth -- not "no userland".

## Where Termux stands

Strong, and stronger than expected. Inside the real app sandbox
(`untrusted_app_27`, not `run-as`): a relocated bootstrap runs bash 5.3.15 from
an empty environment, `termux-exec` redirects shebangs, `curl` reaches Termux's
repo over TLS and `apt update` works.

The unexpected prize is that none of this needs the resolver apparatus. Termux
binaries are Bionic-linked and resolve through netd, so the patched musl, the
compiled-in `etc/resolv.conf` and the c-ares preload -- the whole cost of the
current design -- simply do not apply on that path.

Packages work too, and through a better mechanism than expected. Rewriting the
archive is hooked at `Dir::Bin::dpkg` in apt's own configuration, so `apt install`
stays the command and there is nothing to remember. `apt install -y git` returns
zero, all ten packages reach `ii`, and `git clone` over https then works with no
certificate setup because the bootstrap carries its own.

Verified directly rather than taken on report: in the app sandbox, `git version
2.55.0`, `dpkg --verify git` exits zero, and a fresh clone started for the check
landed at `d0dd1f6`. The cost is about twice an ordinary install's wall time,
paid per install, and it is all recompression -- the rewritten archive is
unpacked seconds later, so it is stored uncompressed.

## The devices

The phone roams between hotspots -- `bash spike/phone.sh` finds it. Its display
sometimes ignores `KEYCODE_WAKEUP`; the process table, the app's files and
`.claude.json` answer most questions without one.

The emulator carries a throwaway `apk.hrness` with a Termux bootstrap unpacked
at `files/usr`, for the tests above. It is disposable: `adb uninstall apk.hrness`.
