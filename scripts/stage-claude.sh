#!/usr/bin/env bash
# Produce a Claude Code binary that runs on Android, and the loader it needs.
#
# Anthropic publishes musl builds of Claude Code. A musl binary asks for one
# thing Android does not have -- /lib/ld-musl-<arch>.so.1 -- and on musl that
# loader is also libc, satisfying the binary's only DT_NEEDED from itself. So
# the whole port is: build one shared library, and run it as a program with the
# binary as its argument.
#
# The library is patched (app/src/main/cpp/harness-exec/musl.patch) to route
# every exec it makes, to read /etc through $HARNESS_ETC, and to list addresses
# without the link dump Android refuses. Nothing in it names a channel, so one
# build serves them all.
#
# Usage: stage-claude.sh [--abi ABI] [--version X.Y.Z] [--out DIR]
#                        [--loader FILE] [--loader-only]
#
#   --abi          x86_64 (default) or arm64-v8a
#   --version      Claude Code version; defaults to the latest on npm
#   --out          staging directory on this machine
#   --loader       use this loader instead of building one. A loader built
#                  anywhere else lacks the patches: its programs cannot start
#                  anything, and name resolution through libc does not work.
#   --loader-only  build the loader and stop. This is what the APK needs: the
#                  binary is downloaded on the device and stored as it arrived.
#
# Building for another architecture takes a cross compiler, passed as CC. Note
# that `zig cc` is not one for this purpose: it is itself a musl toolchain, and
# building musl with it drops musl's own memcpy, memset and libm in favour of
# its compiler runtime, leaving a loader that cannot relocate anything. A
# <target>-linux-gnu-gcc works, and is what building here expects:
#
#   pacman -S aarch64-linux-gnu-gcc      # Arch, Manjaro
#   apt install gcc-aarch64-linux-gnu    # Debian, Ubuntu
set -euo pipefail

MUSL_VERSION=1.2.5
MUSL_SHA256=a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ABI=x86_64
VERSION=
OUT=$ROOT/build/claude
SUPPLIED_LOADER=
LOADER_ONLY=
# musl's tree is thousands of small files, so it is built on a local filesystem.
# Under WSL the repository lives on a 9p mount where that is punishingly slow.
CACHE=${MUSL_CACHE:-${TMPDIR:-/tmp}/harness-musl}

while [ $# -gt 0 ]; do
  case $1 in
    --abi) ABI=$2; shift 2 ;;
    --version) VERSION=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --loader) SUPPLIED_LOADER=$2; shift 2 ;;
    --loader-only) LOADER_ONLY=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

case $ABI in
  x86_64)     MUSL_ARCH=x86_64;  PLATFORM=linux-x64-musl;   DEFAULT_CC=gcc ;;
  arm64-v8a)  MUSL_ARCH=aarch64; PLATFORM=linux-arm64-musl; DEFAULT_CC=aarch64-linux-gnu-gcc ;;
  *) echo "unsupported abi: $ABI" >&2; exit 2 ;;
esac

LOADER=ld-musl-$MUSL_ARCH.so.1
info() { printf '\033[36m==\033[0m %s\n' "$*"; }

# The loader needs a compiler, an archive and its patch; the binary needs a
# manifest read. Only what the requested path uses is demanded, so a host that
# can build a loader is not turned away for lacking jq.
tools="curl make patch"
[ -n "$LOADER_ONLY" ] || tools="$tools jq"
for tool in $tools; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done

mkdir -p "$OUT" "$CACHE"

# --- the loader ---
verify_loader() {
  # A loader that links and installs cleanly can still be missing the symbol
  # table, which only shows up on a device as a page of "symbol not found".
  #
  # The symbols are read once into a variable rather than piped per symbol:
  # under `pipefail`, `grep -q` closing the pipe early kills readelf with
  # SIGPIPE, and a match would report itself as a failure.
  local symbols
  symbols=$(readelf --dyn-syms "$1")
  for symbol in memcpy memset malloc __stack_chk_fail; do
    grep -q " $symbol\$" <<<"$symbols" || {
      echo "$1 does not export $symbol; it cannot relocate anything" >&2
      echo "linked against it" >&2
      exit 1
    }
  done
}

# A cached loader is good for as long as the patch, the router and this script
# are the ones it was built from.
HX=$ROOT/app/src/main/cpp/harness-exec
inputs=$(cat "$HX/musl.patch" "$HX/harness-exec.c" "$HX/harness-exec.h" "${BASH_SOURCE[0]}" \
  | sha256sum | cut -d' ' -f1)
stamp=$CACHE/$MUSL_ARCH/inputs
cached_loader() {
  [ -f "$CACHE/$MUSL_ARCH/$LOADER" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$inputs" ]
}

if [ -n "$SUPPLIED_LOADER" ]; then
  verify_loader "$SUPPLIED_LOADER"
  mkdir -p "$CACHE/$MUSL_ARCH"
  cp "$SUPPLIED_LOADER" "$CACHE/$MUSL_ARCH/$LOADER"
  # A loader built elsewhere carries none of the patches.
  printf '%s' "(supplied)" > "$stamp"
elif ! cached_loader; then
  tarball=$CACHE/musl-$MUSL_VERSION.tar.gz
  if [ ! -f "$tarball" ]; then
    info "downloading musl $MUSL_VERSION"
    curl -fsSL "https://musl.libc.org/releases/musl-$MUSL_VERSION.tar.gz" -o "$tarball.tmp"
    mv "$tarball.tmp" "$tarball"
  fi
  actual=$(sha256sum "$tarball" | cut -d' ' -f1)
  [ "$actual" = "$MUSL_SHA256" ] || {
    echo "musl checksum mismatch: expected $MUSL_SHA256, got $actual" >&2; exit 1; }

  # Extracted fresh every time, so the patch meets the tree it was made against.
  src=$CACHE/musl-$MUSL_VERSION
  rm -rf "$src"
  tar -xzf "$tarball" -C "$CACHE"

  # An app targeting API 29 or later may not execve a file in its data
  # directory, and every program the agent starts is one. The router decides
  # how each exec runs; musl binds its internal calls locally, so patching
  # execve, posix_spawn and the readlinks covers execvp, posix_spawnp, system
  # and popen too.
  #
  # musl opens /etc/resolv.conf, /etc/hosts and /etc/services from inside libc,
  # by way of calls that never reach the PLT -- so nothing outside can redirect
  # them, and on Android there is nothing at /etc to read. Without a resolver
  # musl falls back to a nameserver on loopback that nothing answers, and every
  # name takes its full timeout to fail. The patch reads them from $HARNESS_ETC.
  #
  # At API 30 and later Android refuses an app the netlink link dump, which
  # musl's getifaddrs starts with; the patch lists the addresses without it.
  cp "$HX/harness-exec.c" "$src/src/process/harness-exec.c"
  cp "$HX/harness-exec.h" "$src/src/internal/harness-exec.h"
  patch -d "$src" -p1 --fuzz=0 --no-backup-if-mismatch --quiet < "$HX/musl.patch" \
    || { echo "musl.patch does not apply to musl $MUSL_VERSION" >&2; exit 1; }

  # Android grants an app an allowlist of syscalls that omits the legacy calls
  # bionic never makes, and answers the rest with SECCOMP_RET_KILL_PROCESS. musl
  # reaches for those legacy calls wherever the ABI still offers them, so on
  # x86_64 `access`, `poll`, `pipe`, `dup2` and their kin kill the process on
  # sight. Dropping the numbers makes musl compile the *at variants it already
  # carries for architectures whose ABI never had the legacy calls -- which is
  # also why aarch64 needs none of this.
  if [ "$MUSL_ARCH" = x86_64 ]; then
    for name in access chmod chown dup2 epoll_create epoll_wait eventfd \
                futimesat inotify_init lchown link lstat mkdir mknod pause \
                pipe poll rmdir select signalfd symlink unlink; do
      sed -i "/^#define __NR_$name[[:space:]]/d" \
        "$src/arch/x86_64/bits/syscall.h.in"
    done
  fi

  info "building musl for $MUSL_ARCH"
  build=$CACHE/build-$MUSL_ARCH
  rm -rf "$build"; mkdir -p "$build"
  # Only the shared library is wanted, and it is the loader: musl links libc.so
  # as a PIE whose entry point is the dynamic linker.
  # CC has to be spelled out: given --target alone, musl's configure goes
  # looking for a <target>-gcc that a native build does not have.
  #
  # --export-dynamic has to be spelled out too. Some drivers -- `zig cc` among
  # them -- drop the dynamic symbol table from the shared libc, which links and
  # installs perfectly and then fails at run time with a page of
  # "symbol not found" for every libc function the program wanted.
  #
  # Play rejects a library with a LOAD segment aligned below 16 KB, and a 16 KB
  # device cannot map one.
  (cd "$build" && "$src/configure" \
      --target="$MUSL_ARCH-linux-musl" \
      --disable-static \
      CC="${CC:-$DEFAULT_CC}" \
      CFLAGS="-DHX_MUSL" \
      LDFLAGS="-Wl,--export-dynamic -Wl,-z,max-page-size=16384" >configure.log 2>&1 \
    && make -j"$(nproc)" lib/libc.so >build.log 2>&1) \
    || { echo "musl build failed; see $build/{configure,build}.log" >&2; exit 1; }

  verify_loader "$build/lib/libc.so"

  mkdir -p "$CACHE/$MUSL_ARCH"
  cp "$build/lib/libc.so" "$CACHE/$MUSL_ARCH/$LOADER"
  printf '%s' "$inputs" > "$stamp"
fi
cp "$CACHE/$MUSL_ARCH/$LOADER" "$OUT/$LOADER"
chmod 755 "$OUT/$LOADER"
info "loader: $OUT/$LOADER ($(stat -c%s "$OUT/$LOADER") bytes)"

if [ -n "$LOADER_ONLY" ]; then
  exit 0
fi

# --- the binary ---
if [ -z "$VERSION" ] && [ -f "$OUT/VERSION" ]; then
  VERSION=$(tr -d '\r\n' < "$OUT/VERSION")
fi
if [ -z "$VERSION" ]; then
  VERSION=$(curl -fsSL --max-time 10 \
    https://registry.npmjs.org/@anthropic-ai/claude-code/latest | jq -er .version)
fi
printf '%s' "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || { echo "unexpected version string: $VERSION" >&2; exit 1; }
info "claude code $VERSION ($PLATFORM)"

base=https://downloads.claude.ai/claude-code-releases/$VERSION
[ -f "$OUT/manifest.json" ] \
  || curl -fsSL --max-time 30 "$base/manifest.json" -o "$OUT/manifest.json"
expected=$(jq -er ".platforms[\"$PLATFORM\"].checksum" < "$OUT/manifest.json")

# The download is kept as it arrived, so its checksum stays meaningful. Patching
# happens on a copy.
pristine=$OUT/claude-$VERSION-$PLATFORM
if [ ! -f "$pristine" ] || [ "$(sha256sum "$pristine" | cut -d' ' -f1)" != "$expected" ]; then
  info "downloading (~320 MB)"
  curl -fSL --max-time 900 "$base/$PLATFORM/claude" -o "$pristine.tmp"
  actual=$(sha256sum "$pristine.tmp" | cut -d' ' -f1)
  # The binary and the manifest come from the same host, so this catches a
  # truncated or corrupted download. It is not code signing.
  [ "$actual" = "$expected" ] || {
    rm -f "$pristine.tmp"
    echo "checksum mismatch: expected $expected, got $actual" >&2; exit 1; }
  mv "$pristine.tmp" "$pristine"
fi

binary=$OUT/claude
cp "$pristine" "$binary"
chmod 755 "$binary"

printf '%s\n' "$VERSION" > "$OUT/VERSION"
info "staged in $OUT"
ls -l "$OUT"
