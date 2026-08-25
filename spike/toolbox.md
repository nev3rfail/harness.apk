# A userland for the agent

The agent's Bash tool runs against Android's shell: toybox, and `sh` that is not
bash. On the phone `toybox` lists 209 applets, which is more than the complaint
suggests -- `awk`, `sed`, `diff`, `patch`, `find`, `xargs` are all there. What is
missing is `git`, anything that fetches a URL, bash itself, and the depth of the
applets that do exist. This is what it costs to put busybox, bash and git in the
app's own prefix, staged the way the loader is.

Everything below with a size next to it was built, and everything quoted from a
shell prompt was run. What was not verified is listed at the end.

## The one thing that does not go away

A static binary needs no loader, so the ELF-interpreter half of the problem
disappears. The resolver half does not.

musl opens `/etc/resolv.conf`, `/etc/hosts` and `/etc/services` from inside libc.
Linking statically does not change the path -- it copies it into the executable.
On the phone, `/etc` is a symlink to `/system/etc`, which is read-only and holds
no `resolv.conf`:

    $ adb shell 'ls -ld /etc; ls -l /etc/resolv.conf'
    lrw-r--r-- 1 root root 11 /etc -> /system/etc
    ls: /etc/resolv.conf: No such file or directory

So anything in the toolbox that resolves a name -- `busybox wget`, `git clone`,
`curl` -- needs the same treatment `stage-claude.sh` already gives the loader: the
prefix patched into musl's network sources before musl is built. The difference is
that the toolbox needs a *static* libc from that patched tree, and the script
currently builds only the shared one.

Verified on x86_64, where the binary can be run:

    $ musl-gcc -static -o t2 t2.c        # sysroot patched for /tmp/fakeprefix
    $ rm -rf /tmp/fakeprefix; ./t2 example.com
    getaddrinfo: Try again
    $ printf 'nameserver 8.8.8.8\n' > /tmp/fakeprefix/etc/resolv.conf; ./t2 example.com
    172.66.147.243
    $ strace -e trace=open ./t2 example.com | grep resolv
    open("/tmp/fakeprefix/etc/resolv.conf", O_RDONLY|O_LARGEFILE|O_CLOEXEC) = 3

The same libc, unpatched, cannot resolve anything on the phone. Alpine's static
busybox, pushed to the device, says so plainly:

    $ ./bin/busybox wget -q -O - https://example.com
    wget: bad address 'example.com'

**x86_64 is already covered by something else.** The syscall shim sets
`PTRACE_O_TRACEFORK | TRACEVFORK | TRACECLONE` and rewrites any path beginning
`/etc/` to `$SYSCALL_SHIM_ETC`, so every descendant of the agent -- a shell it
spawns, and anything that shell runs -- gets the redirect for free. The shim runs
only on x86_64. On a phone there is no shim, so the patched libc is the only
answer. Building both architectures against the patched musl makes the two behave
the same, and costs nothing extra.

## busybox

Static, against musl, both architectures. `defconfig` plus `CONFIG_STATIC=y`,
`FEATURE_SH_STANDALONE`, `FEATURE_PREFER_APPLETS`, and `SELINUX`, `TC`, `NSENTER`,
`UNSHARE`, `FEATURE_UTMP`, `FEATURE_WTMP` off.

    make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- CC=$SYSROOT/bin/musl-gcc defconfig
    # edit .config
    make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- CC=$SYSROOT/bin/musl-gcc -j16

| | size | applets |
|---|---|---|
| x86_64 | 1,153,512 | 394 |
| aarch64 | 1,247,120 | 394 |

Ran on the phone and on the emulator. The applet list covers what the complaint is
actually about: `awk sed grep find xargs tar diff patch wget vi less sort uniq cut
tr head tail stat du df ps top kill env realpath readlink base64 sha256sum unzip
gzip`, and `ash` as `sh`.

Two notes. `busybox wget` carries its own TLS through the `ssl_client` applet, so
HTTPS fetch is in that 1.2 MB and needs no openssl -- but busybox's TLS holds no
CA store and does not validate the chain, so it is an encrypted transport and not
an authenticated one. Fetching a URL the agent was told about is fine; fetching
one it will act on is not, and that is what the openssl-backed transport in the git
tier is for. busybox says so itself, on the device: `wget: note: TLS certificate
validation not implemented`.

And busybox has its own hardcoded `/etc/resolv.conf` for `nslookup` and `udhcpc`.
The libc path is redirected by the musl patch, that one is not, and neither applet
matters here.

The symlink farm is what makes the applets reachable from bash rather than only
from busybox's own `sh`. `make CONFIG_PREFIX=<dir> install` writes it at build
time, so nothing has to run a target binary.

## bash

bash 5.2.37, static, `--without-bash-malloc --disable-nls --enable-static-link`,
with the dozen `bash_cv_*` answers a cross build cannot measure for itself.

| | size |
|---|---|
| x86_64 | 1,130,384 |
| aarch64 | 1,243,800 |

Ran on the phone: arrays, `[[`, arithmetic, `$BASH_VERSION`.

bash reads nothing from `/etc` when running `bash -c`, which is how the agent's
Bash tool uses it -- traced, and the trace is empty. The `/etc` paths in the
binary are `/etc/inputrc` and `/etc/termcap` for interactive readline, `/etc/passwd`
through musl's `getpwuid` for `~user` and `\u`, and musl's network paths that bash
itself never uses. None of them need the prefix trick: an absent `/etc/passwd`
costs a prompt that says it has no name, and an absent termcap costs readline its
terminal description, not its line editing.

`bash_cv_termcap_lib=gnutermcap` builds the bundled termcap, so there is no
ncurses dependency and no terminfo tree to stage.

## git

Two tiers, and the gap between them is the whole cost.

**Local only.** zlib and nothing else, one `make`:

    make CC=$SYSROOT/bin/musl-gcc AR=aarch64-linux-gnu-ar \
      CFLAGS="-Os -std=gnu17 -I$DEPS/include" LDFLAGS="-static -L$DEPS/lib" \
      prefix=$PREFIX SHELL_PATH=/bin/sh \
      NO_CURL=1 NO_GETTEXT=1 NO_TCLTK=1 NO_PERL=1 NO_PYTHON=1 \
      NO_EXPAT=1 NO_ICONV=1 NO_OPENSSL=1 NO_INSTALL_HARDLINKS=1 \
      NO_REGEX=NeedsStartEnd git

| | size |
|---|---|
| x86_64 | 3,278,360 |
| aarch64 | 3,328,736 |

142 builtins in the one binary. `git version 2.47.1` on the phone; `init`, `add`,
`commit`, `diff --stat`, `log` verified where the binary could be run.

**With HTTPS.** zlib, openssl 3.0.15 (`no-shared no-dso no-tests no-legacy`), curl
8.11.1 (`--disable-shared --without-libpsl --without-libidn2 --without-nghttp2
--without-brotli --without-zstd`), then git with `CURL_LDFLAGS="-lcurl -lssl
-lcrypto -lz"`.

| | libz.a | libssl.a | libcrypto.a | libcurl.a | git | git-remote-http |
|---|---|---|---|---|---|---|
| aarch64 | 146,628 | 1,272,570 | 9,420,134 | 1,319,016 | 3,328,784 | 6,681,224 |
| x86_64 | 150,148 | 1,253,610 | 9,453,852 | 1,321,256 | 3,278,424 | 6,950,456 |

`git clone --depth 1 https://github.com/git/git.git` completes with the x86_64
build, run on the host. The static curl CLI from the same tree is 5,672,856
(aarch64).

A full `make install` produces 40 MB across 218 entries, most of it things a phone
has no use for: `git-daemon`, `git-http-backend`, `git-imap-send`, `git-http-fetch`,
`git-cvsserver`, `scalar`, `git-shell`. What is actually needed is `bin/git`,
`libexec/git-core/git-remote-http` with `git-remote-https` symlinked to it, the 144
builtin symlinks, 83 KB of shell helpers and 27 KB of templates -- around **10 MB**
for the aarch64 HTTPS tier against **3.3 MB** without it. Sizes move a few percent
with the compiler; the numbers above are the GCC 11 musl cross toolchain, and the
ones under *End to end* are `aarch64-linux-gnu-gcc` 16 against the patched sysroot,
which is the arrangement being recommended.

### Certificates

The device has a trust store and it cannot be used as a `CApath`. Both
`/system/etc/security/cacerts` and `/apex/com.android.conscrypt/cacerts` hold 145
PEM files under hashed names, readable as the app's uid -- but the hash is the one
OpenSSL used before 1.0:

    filename         : 01419da9
    subject_hash     : 8d89cda1
    subject_hash_old : 01419da9
    subject=C=US, O=Microsoft Corporation, CN=Microsoft ECC Root CA 2017

A modern OpenSSL looks up `subject_hash`, finds nothing, and every verification
fails with `unable to get local issuer certificate` -- which it does, both with the
path compiled in and with `GIT_SSL_CAPATH` pointed at either directory.

Concatenating the directory into one file fixes it, and the concatenation has to
happen on the device because that is where the certificates are:

    $ cat /system/etc/security/cacerts/*.0 > $PREFIX/etc/ssl/certs/ca-certificates.crt
    $ grep -c 'BEGIN CERTIFICATE' $PREFIX/etc/ssl/certs/ca-certificates.crt
    145

That is 758 KB, costs nothing to ship, follows the device's own trust decisions,
and belongs next to the resolver file the app already writes. Build curl with
`--with-ca-bundle=$PREFIX/etc/ssl/certs/ca-certificates.crt` so nothing has to set
an environment variable.

Do not build with `--with-ca-path`: a compiled-in bundle path also wins over
`GIT_SSL_CAPATH` at run time, so the two options are not interchangeable and the
wrong one fails with `error setting certificate file` before the path is ever
consulted.

### End to end, on the phone

With the toolbox built against a musl patched for its own prefix, the resolver file
at that prefix, and a bundle concatenated from the device's certificates:

    $ ./bin/busybox wget -q -O - https://example.com | head -c 60
    wget: note: TLS certificate validation not implemented
    <!doctype html><html lang="en"><head><title>Example Domain</
    $ git clone --depth 1 https://github.com/git/git.git g3
    Cloning into 'g3'... Updating files: 100% (4849/4849), done.
    $ cd g3 && git log --oneline -1
    593c42f The 17th batch
    $ bash -c 'a=(one two); echo ${a[1]}; busybox sha256sum bin/git'
    two
    0dac19bcf23d1e9d...

Sizes for that tree, aarch64, built against the patched sysroot with
`aarch64-linux-gnu-gcc`:

| | size |
|---|---|
| busybox | 1,247,120 |
| bash | 1,214,720 |
| git | 3,160,088 |
| git-remote-http | 6,086,736 |

11.7 MB together. The same tree also produced a 4.8 MB `curl` CLI that would not
run, for the libtool reason below; it is optional anyway, since `git-remote-http`
carries the same libcurl and `busybox wget` covers the unauthenticated case.

**Prebuilt static gits exist** -- [darkvertex/static-git](https://github.com/darkvertex/static-git),
[tiiuae/aarch64_bin_builder](https://github.com/tiiuae/aarch64_bin_builder),
[dfabric/apps-static](https://github.com/dfabric/apps-static) -- and every one of
them solves the compile and not the resolver. They are fine for the local-only
tier and cannot clone on the phone. Not measured; the objection is structural.

## What broke, and why

Every one of these cost a build. They are listed so the next person spends the
time on something else.

1. **git wants openssl headers even with `NO_CURL=1`.** `git-compat-util.h`
   includes `openssl/ssl.h` unless `NO_OPENSSL=1`. git 2.47 hashes with
   sha1collisiondetection, so nothing is lost.

2. **`SHELL_PATH` is two things.** It is the shell git's build runs *and* the
   shebang of the 32 installed shell helpers. Set to the device path, the build
   dies at `please_set_SHELL_PATH_to_a_more_modern_shell`. Build with the host
   `/bin/sh` and rewrite the shebangs when staging.

3. **`make install` re-runs `all`.** Different flags mean a different
   `GIT-CFLAGS`, so an install invocation missing `CC` silently rebuilds the whole
   tree with the host compiler and stages x86_64 binaries into an aarch64 prefix.
   Pass one identical argument list to both targets.

4. **A static musl needs `AR` and `RANLIB`.** `stage-claude.sh` never hits this
   because `--disable-static` means no archive is ever created. Drop that flag and
   musl's configure derives `aarch64-linux-musl-ar`, which does not exist.

5. **musl ships no Linux UAPI headers.** libc builds without them; nothing else
   does -- busybox stops at `linux/kd.h`. A sysroot needs `linux/`, `asm/`,
   `asm-generic/` from a kernel `headers_install` (or lifted from an existing
   cross toolchain, which is what the prototype did).

6. **`aarch64-linux-gnu-gcc` is a glibc compiler.** It builds musl itself, because
   musl compiles freestanding -- which is why the loader has never had a problem.
   Linking real programs against musl with it needs two fixes. GCC 16's aarch64
   driver emits `-latomic_asneeded`, and `musl-gcc.specs` replaces the library
   search path that would resolve it, so a copy of `libatomic.a` under that name
   goes in the sysroot. And `libgcc.a`'s `lse-init.o` calls `__getauxval`, a glibc
   symbol musl does not have, which `-mno-outline-atomics` in the specs keeps out.
   A musl-targeting cross toolchain has neither problem; it also costs a 110 MB
   download from a site that publishes no checksums, or a 40-minute
   musl-cross-make build, and ships its own unpatched musl that would have to be
   replaced anyway.

7. **`musl-gcc` is written for dynamic linking.** Its specs hardcode
   `-dynamic-linker /lib/ld-musl-<arch>.so.1` and `Scrt1.o`, and say nothing about
   `-static-pie` -- which therefore produces, silently, a dynamically linked
   binary with an interpreter. Use `-static`. Static non-PIE runs on Android 15;
   the busybox, bash and git above are all non-PIE and all ran on the phone.

8. **`-std=gnu23` is the default from GCC 14 on, and neither bash nor git
   survives it.** bash 5.2's K&R prototypes become "too many arguments", and its
   bundled termcap calls `write` with no `<unistd.h>` in scope. git 2.47's
   `struct thread_local` in `builtin/index-pack.c` collides with the C23 keyword,
   which reports itself as "storage class specified for parameter". `-std=gnu17`
   for both; bash additionally wants `-Wno-implicit-function-declaration
   -Wno-incompatible-pointer-types`. A GCC 11 cross toolchain shows none of this,
   which is how a build can pass on one machine and not another.

9. **openssl 3.0 rejects `no-docs`.** It arrived in 3.2.

10. **`LDFLAGS=-static` does not make curl's CLI static.** curl links through
    libtool, where `-static` means "prefer the static libtool libraries", not
    "static executable"; the result is a dynamically linked `curl` that dies on
    Android with `No such file or directory` because the interpreter is missing.
    `-all-static` is the libtool spelling. git links with plain gcc and is not
    affected, which is why `git-remote-http` came out static from the same tree.

## The other route: Alpine packages

Alpine is musl, dynamically linked, and the harness already builds and stages a
musl loader with the prefix compiled in -- which is exactly what an Alpine binary
asks for. So `patchelf --set-interpreter $PREFIX/ld-musl-aarch64.so.1 --set-rpath
$PREFIX/lib`, plus a `libc.musl-aarch64.so.1` symlink pointing at the loader, and
the package runs. No cross compiler, no build, the same mechanism `stage-claude.sh`
already applies to the agent binary.

Resolving `git bash busybox-static curl ca-certificates-bundle` out of
`APKINDEX` gives 24 packages and 25.2 MB installed; the staged tree after
patching is 24 MB. On the phone:

    BusyBox v1.37.0 (2025-11-23) multi-call binary.
    GNU bash, version 5.2.37(1)-release (aarch64-alpine-linux-musl)
    curl 8.14.1 (aarch64-alpine-linux-musl) ... c-ares/1.34.8 ...
    git version 2.47.3

All four run. And then:

    $ ./bin/git clone --depth 1 https://github.com/git/git.git t
    fatal: unable to access 'https://github.com/...': Could not resolve host: github.com
    $ ./bin/curl --dns-servers 8.8.8.8 https://example.com
    curl: (77) error setting certificate file: /etc/ssl/cert.pem

Alpine builds curl against **c-ares**, which carries its own resolver and reads the
real `/etc/resolv.conf` -- the patched libc never sees the lookup. `--dns-servers`
fixes it for the curl CLI and there is no equivalent for git, which drives libcurl
directly. This is the same split the agent already has -- `fetch` through libc, the
`dns` module through c-ares -- and there it is answered by a Bun preload, which is
a lever a C program does not offer.

So the Alpine route delivers busybox, bash, a shell userland and every local git
operation for one patchelf loop and no toolchain, and cannot clone over the
network without replacing libcurl -- at which point the build is back. It is worth
keeping in mind for anything later that is a package rather than a compiler
problem: ripgrep, jq, python.

## Where this belongs

**A sibling script, sharing the musl step.** `stage-claude.sh` and the toolbox need
the *same patched musl* and differ in everything else -- the agent binary is
downloaded per release and the toolbox is built once and changes almost never.
Folding six source builds into `stage-claude.sh` makes every agent update pay for
them; duplicating the `/etc` patch in a second script is exactly the drift the
existing comments warn about.

So: lift the musl build out into `scripts/musl.sh` as one function that both
scripts call. `stage-claude.sh` gets shorter.

    # scripts/musl.sh
    -  the download, checksum, /etc patch and syscall-number patch, verbatim
    -  configure without --disable-static, with AR/RANLIB, then `make install`
    -  drop kernel UAPI headers into the sysroot
    -  the two aarch64 spec fixes
    -  sets MUSL_SYSROOT and MUSL_LOADER; caches on arch + prefix as today

    # scripts/stage-claude.sh
    -MUSL_VERSION=1.2.5
    -MUSL_SHA256=a9a1...
    +. "$ROOT/scripts/musl.sh"
    ...
    -  [the ~70 lines from `verify_loader` through the cached-loader branch]
    +musl_sysroot "$MUSL_ARCH" "$PREFIX" "$CACHE"
    +cp "$MUSL_LOADER" "$OUT/$LOADER"

    # scripts/stage-toolbox.sh  (new)
    +  --abi/--prefix/--out as stage-claude.sh, plus --with-git and --with-https
    +. "$ROOT/scripts/musl.sh"
    +musl_sysroot "$MUSL_ARCH" "$PREFIX" "$CACHE"
    +busybox   -> $OUT/bin/busybox + `make CONFIG_PREFIX=$OUT install` symlinks
    +bash      -> $OUT/bin/bash
    +[git]     -> zlib, then git; $OUT/bin/git and the builtin symlinks
    +[https]   -> openssl, curl, git-remote-http, and the shebang rewrite

`--loader` keeps its meaning and `verify_loader` keeps its job; a matching
`verify_toolbox` that greps the staged binaries for the prefix would catch the
failure mode where the sysroot was built for a different one.

**The app has to change too, and the script cannot do it for it.** `Agent.kt` sets
`SHELL = /system/bin/sh` and does not touch `PATH`. A staged toolbox that nothing
points at is invisible: `SHELL` wants the staged bash and `PATH` wants
`$PREFIX/bin` in front, both only when the files are there, the same way the
staged binary is already checked with `canExecute()`. If HTTPS git is wanted, the
same block writes the CA bundle -- one concatenation of
`/system/etc/security/cacerts`, right where it already writes `resolv.conf`.

## What it costs

Times are wall clock on 16 cores, measured from the prototype's own logs; sizes are
aarch64 staged.

| | build | staged | needs |
|---|---|---|---|
| musl sysroot | ~1 min | -- | shared with the loader |
| busybox | ~40 s | 1.25 MB + 394 symlinks | nothing new |
| bash | ~70 s, mostly configure | 1.21 MB | nothing new |
| git, local | ~30 s + zlib | 3.2 MB + 144 symlinks + 110 KB of helpers | nothing new |
| git, HTTPS | +~5 min | +6.1 MB | openssl and curl sources, a CA bundle written by the app |

The recommendation is to take the first three rows and stop. 2.5 MB of busybox and
bash is the entire "the shell is toybox" complaint, and it builds in two minutes.
git without a network is another 3.2 MB and one `make`.

HTTPS git is the only line worth arguing about. It triples git's footprint, adds
openssl and curl to the list of things that need watching for CVEs, obliges the app
to maintain a certificate bundle, and serves a use the README calls the occasional
case rather than the design centre. It works -- the clone above is real -- but it
is a decision, not a default.

## Not verified

- The build times are single measurements from one machine, not averages.
- The x86_64 toolbox was run from `adb shell`, which is not the app's SELinux
  domain or its seccomp filter. It executes and it resolves; whether the shim's
  translation reaches every syscall busybox and git make on x86_64 is untested,
  and is the one place a surprise is likely. On aarch64 the question does not
  arise, and the aarch64 toolbox was exercised end to end.
- Nothing was run inside the app itself. Staging into `files/claude/` and driving
  the toolbox from the agent's own Bash tool is the next check, and it is the one
  that would catch a `PATH` or `SHELL` mistake.
- The certificate bundle was concatenated by hand on the device. `Agent.kt`
  writing it at launch is a design, not a tested change.
- The Alpine route's HTTPS limit was diagnosed from curl's own build line and
  confirmed by `--dns-servers` clearing the failure; libcurl was not rebuilt
  without c-ares to prove the converse.
