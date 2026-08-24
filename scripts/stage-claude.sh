#!/usr/bin/env bash
# Produce a Claude Code binary that runs on Android, and the loader it needs.
#
# Anthropic publishes musl builds of Claude Code. A musl binary asks for one
# thing Android does not have -- /lib/ld-musl-<arch>.so.1 -- and on musl that
# loader is also libc, satisfying the binary's only DT_NEEDED from itself. So
# the whole port is: build one shared library, and point the binary at where it
# will live on the device.
#
# Usage: stage-claude.sh [--abi ABI] [--version X.Y.Z] [--prefix DIR] [--out DIR]
#
#   --abi      x86_64 (default) or arm64-v8a
#   --version  Claude Code version; defaults to the latest on npm
#   --prefix   where the staged files will live on the device, baked into the
#              binary's ELF interpreter
#   --out      staging directory on this machine
#
# Cross-compiling needs a compiler for the target: pass CC, e.g.
#   CC="zig cc -target aarch64-linux-musl" scripts/stage-claude.sh --abi arm64-v8a
set -euo pipefail

MUSL_VERSION=1.2.5
MUSL_SHA256=a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ABI=x86_64
VERSION=
PREFIX=/data/data/com.ghostty.android/files/claude
OUT=$ROOT/build/claude
# musl's tree is thousands of small files, so it is built on a local filesystem.
# Under WSL the repository lives on a 9p mount where that is punishingly slow.
CACHE=${MUSL_CACHE:-${TMPDIR:-/tmp}/harness-musl}

while [ $# -gt 0 ]; do
  case $1 in
    --abi) ABI=$2; shift 2 ;;
    --version) VERSION=$2; shift 2 ;;
    --prefix) PREFIX=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

case $ABI in
  x86_64)     MUSL_ARCH=x86_64;  PLATFORM=linux-x64-musl ;;
  arm64-v8a)  MUSL_ARCH=aarch64; PLATFORM=linux-arm64-musl ;;
  *) echo "unsupported abi: $ABI" >&2; exit 2 ;;
esac

LOADER=ld-musl-$MUSL_ARCH.so.1
info() { printf '\033[36m==\033[0m %s\n' "$*"; }

for tool in curl jq patchelf make; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done

mkdir -p "$OUT" "$CACHE"

# --- the loader ---
if [ ! -f "$CACHE/$MUSL_ARCH/$LOADER" ]; then
  tarball=$CACHE/musl-$MUSL_VERSION.tar.gz
  if [ ! -f "$tarball" ]; then
    info "downloading musl $MUSL_VERSION"
    curl -fsSL "https://musl.libc.org/releases/musl-$MUSL_VERSION.tar.gz" -o "$tarball.tmp"
    mv "$tarball.tmp" "$tarball"
  fi
  actual=$(sha256sum "$tarball" | cut -d' ' -f1)
  [ "$actual" = "$MUSL_SHA256" ] || {
    echo "musl checksum mismatch: expected $MUSL_SHA256, got $actual" >&2; exit 1; }

  src=$CACHE/musl-$MUSL_VERSION
  [ -d "$src" ] || tar -xzf "$tarball" -C "$CACHE"

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
  (cd "$build" && "$src/configure" \
      --target="$MUSL_ARCH-linux-musl" \
      --disable-static \
      CC="${CC:-gcc}" >configure.log 2>&1 \
    && make -j"$(nproc)" lib/libc.so >build.log 2>&1) \
    || { echo "musl build failed; see $build/{configure,build}.log" >&2; exit 1; }

  mkdir -p "$CACHE/$MUSL_ARCH"
  cp "$build/lib/libc.so" "$CACHE/$MUSL_ARCH/$LOADER"
fi
cp "$CACHE/$MUSL_ARCH/$LOADER" "$OUT/$LOADER"
chmod 755 "$OUT/$LOADER"
info "loader: $OUT/$LOADER ($(stat -c%s "$OUT/$LOADER") bytes)"

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

patchelf --set-interpreter "$PREFIX/$LOADER" "$binary"
info "interpreter: $(patchelf --print-interpreter "$binary")"

printf '%s\n' "$VERSION" > "$OUT/VERSION"
info "staged in $OUT"
ls -l "$OUT"
