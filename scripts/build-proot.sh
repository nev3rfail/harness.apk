#!/usr/bin/env bash
# Build the proot the APK packages, one set per ABI:
#
#   libproot.so         proot, a PIE with libandroid-shmem compiled in. It names
#                       libproot-talloc.so in DT_NEEDED and finds it through
#                       DT_RUNPATH $ORIGIN.
#   libproot-talloc.so  talloc as a shared library with that soname.
#   libproot-loader.so  proot's loader, a static EXEC.
#
# Programs shipped as lib*.so, because the native library directory is
# executable whatever the app targets. Everything is linked for 16 KB pages.
#
# The source is termux/proot at PROOT_TAG, with the recipe termux-packages
# builds it with at TERMUX_PACKAGES_COMMIT (-DARG_MAX=131072,
# PROOT_WITH_LIBANDROID_SHMEM, include-string.patch), then the patches that
# app/src/main/cpp/proot/series lists, in order. talloc and libandroid-shmem
# come from pinned tarballs. Every download is checked against its sha256 and
# kept in build/proot/dl, so a tree holding those files builds offline.
#
# Usage: build-proot.sh [ABI ...]        defaults to both
#        build-proot.sh --host DIR       the same proot for this machine, into
#                                        DIR, for the host tests; no
#                                        libandroid-shmem and no recipe flags
#        build-proot.sh --source FILE    write the corresponding source of the
#                                        packaged files to FILE, a .tar.gz
#        build-proot.sh --check-termux   fail when termux.net's proot package
#                                        is another version than PROOT_TAG
#
# The NDK is $ANDROID_NDK_ROOT, else $ANDROID_HOME/ndk/$NDK_REVISION, else the
# pinned Linux NDK, downloaded. Its revision must be NDK_REVISION.
set -euo pipefail

PROOT_TAG=v5.1.107.80
PROOT_SHA256=d237b21b6d84a3acb00507f96251dcf2bbfbee9ffc66ab6258a3f86ef7874186
TERMUX_PACKAGES_COMMIT=57401119f9988f79fa095398a9b33b03380222b3
INCLUDE_STRING_SHA256=c4bb9ef513c2b87c65d497d4be3eaca2431425c5e09863ea5f7acf66bdd2f8ca
TALLOC_VERSION=2.4.3
TALLOC_SHA256=dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd
SHMEM_VERSION=0.7
SHMEM_SHA256=1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867
# app/build.gradle.kts's ndkVersion.
NDK_REVISION=29.0.14206865
NDK_ZIP=android-ndk-r29-linux.zip
NDK_SHA256=4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf
# The app's minSdk.
API=30

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PATCHES=$ROOT/app/src/main/cpp/proot
DL=$ROOT/build/proot/dl
# Extracted and built on a local filesystem. Under WSL the repository lives on
# a 9p mount where thousands of small files are punishingly slow.
CACHE=${PROOT_CACHE:-${TMPDIR:-/tmp}/harness-proot}

info() { printf '\033[36m==\033[0m %s\n' "$*"; }

sha256() { sha256sum "$1" | cut -d' ' -f1; }

# fetch NAME SHA256 URL...: DL/NAME, from the first URL that serves it.
fetch() {
  local name=$1 sum=$2 url
  shift 2
  if [ -f "$DL/$name" ] && [ "$(sha256 "$DL/$name")" = "$sum" ]; then return; fi
  mkdir -p "$DL"
  for url in "$@"; do
    info "downloading $name"
    if curl -fsSL --retry 3 "$url" -o "$DL/$name.tmp" \
        && [ "$(sha256 "$DL/$name.tmp")" = "$sum" ]; then
      mv "$DL/$name.tmp" "$DL/$name"
      return
    fi
    echo "$url: download failed or checksum is not $sum" >&2
  done
  rm -f "$DL/$name.tmp"
  exit 1
}

fetch_sources() {
  fetch "proot-$PROOT_TAG.zip" "$PROOT_SHA256" \
    "https://github.com/termux/proot/archive/$PROOT_TAG.zip"
  fetch include-string.patch "$INCLUDE_STRING_SHA256" \
    "https://raw.githubusercontent.com/termux-play-store/termux-packages/$TERMUX_PACKAGES_COMMIT/packages/proot/include-string.patch"
  fetch "talloc-$TALLOC_VERSION.tar.gz" "$TALLOC_SHA256" \
    "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" \
    "https://ftp.samba.org/pub/talloc/talloc-$TALLOC_VERSION.tar.gz"
  fetch "libandroid-shmem-$SHMEM_VERSION.tar.gz" "$SHMEM_SHA256" \
    "https://github.com/termux/libandroid-shmem/archive/refs/tags/v$SHMEM_VERSION.tar.gz"
}

# The files the corresponding source is made of, relative to ROOT.
source_files() {
  printf '%s\n' \
    scripts/build-proot.sh \
    "build/proot/dl/proot-$PROOT_TAG.zip" \
    build/proot/dl/include-string.patch \
    "build/proot/dl/talloc-$TALLOC_VERSION.tar.gz" \
    "build/proot/dl/libandroid-shmem-$SHMEM_VERSION.tar.gz"
  (cd "$ROOT" && find app/src/main/cpp/proot -type f | sort)
}

# --- modes that build nothing ---
case ${1:-} in
  --check-termux)
    for arch in aarch64 x86_64; do
      # Read whole before awk stops early, which would fail curl's write.
      packages=$(curl -fsSL "https://termux.net/dists/stable/main/binary-$arch/Packages")
      version=$(awk '$0 == "Package: proot" { p = 1 } p && /^Version:/ { print $2; exit }' \
        <<<"$packages")
      # A Debian revision after the dash is Termux's own rebuild of that tag.
      if [ "v${version%%-*}" != "$PROOT_TAG" ]; then
        echo "termux.net ships proot '$version' for $arch; the packaged proot is $PROOT_TAG" >&2
        exit 1
      fi
      echo "termux.net ships proot $version for $arch"
    done
    exit 0
    ;;
  --source)
    [ $# -eq 2 ] || { echo "usage: $0 --source FILE" >&2; exit 2; }
    fetch_sources
    printf '%s\n' "$NDK_REVISION" > "$ROOT/build/proot/NDK_REVISION"
    out=$(realpath -m "$2")
    (cd "$ROOT" && { source_files; echo build/proot/NDK_REVISION; } \
      | tar -czf "$out" --owner=0 --group=0 -T -)
    info "source: $out ($(stat -c%s "$out") bytes)"
    exit 0
    ;;
esac

HOST_OUT=
ABIS=()
while [ $# -gt 0 ]; do
  case $1 in
    --host) HOST_OUT=$2; shift 2 ;;
    -*) echo "unknown argument: $1" >&2; exit 2 ;;
    *) ABIS+=("$1"); shift ;;
  esac
done
[ -n "$HOST_OUT" ] || [ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64-v8a x86_64)

for tool in curl make patch python3 unzip readelf; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done

mkdir -p "$CACHE"
fetch_sources

# --- the NDK ---
find_ndk() {
  local ndk=${ANDROID_NDK_ROOT:-}
  if [ -z "$ndk" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$NDK_REVISION" ]; then
    ndk=$ANDROID_HOME/ndk/$NDK_REVISION
  fi
  if [ -z "$ndk" ]; then
    ndk=$CACHE/ndk-$NDK_REVISION
    if [ ! -f "$ndk/source.properties" ]; then
      fetch "$NDK_ZIP" "$NDK_SHA256" "https://dl.google.com/android/repository/$NDK_ZIP"
      info "extracting the NDK"
      rm -rf "$ndk" "$ndk.tmp"
      mkdir -p "$ndk.tmp"
      unzip -q "$DL/$NDK_ZIP" -d "$ndk.tmp"
      mv "$ndk.tmp"/android-ndk-* "$ndk"
      rmdir "$ndk.tmp"
    fi
  fi
  local revision
  revision=$(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties")
  [ "$revision" = "$NDK_REVISION" ] || {
    echo "the NDK at $ndk is $revision, not $NDK_REVISION" >&2; exit 1; }
  NDK=$ndk
  TOOLCHAIN=$ndk/toolchains/llvm/prebuilt/linux-x86_64
  [ -d "$TOOLCHAIN" ] || { echo "no linux-x86_64 toolchain under $ndk" >&2; exit 1; }
}

# --- talloc ---
# build_talloc WORK CC AR [cross]: WORK/lib/libtalloc.so, whose soname is
# libproot-talloc.so, and WORK/talloc/talloc.h. talloc's own configure writes
# config.h; the library is linked here so it carries that soname and the page
# size. It is made of the three files talloc's build links into it.
build_talloc() {
  local work=$1 cc=$2 ar=$3 cross=${4:-} src=$1/talloc
  rm -rf "$src"
  mkdir -p "$src" "$work/lib"
  tar -xzf "$DL/talloc-$TALLOC_VERSION.tar.gz" -C "$src" --strip-components=1
  local args=(--disable-rpath --disable-python)
  if [ -n "$cross" ]; then
    # The answers termux-packages' libtalloc recipe gives.
    cat > "$src/cross-answers.txt" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF
    args+=(--cross-compile --cross-answers=cross-answers.txt)
  fi
  (cd "$src" && CC=$cc AR=$ar ./configure "${args[@]}" >configure.log 2>&1) \
    || { echo "talloc configure failed; see $src/configure.log" >&2; exit 1; }

  local flags=(-O2 -fPIC -fvisibility=hidden -D_GNU_SOURCE=1 -D_XOPEN_SOURCE_EXTENDED=1
               -D__STDC_WANT_LIB_EXT1__=1
               -I"$src/bin/default" -I"$src/lib/replace" -I"$src")
  $cc "${flags[@]}" -D_PUBLIC_=_PRIVATE_ -c "$src/lib/replace/replace.c" -o "$src/replace.o"
  $cc "${flags[@]}" -D_PUBLIC_=_PRIVATE_ -c "$src/lib/replace/closefrom.c" -o "$src/closefrom.o"
  $cc "${flags[@]}" -c "$src/talloc.c" -o "$src/talloc.o"
  $cc -shared -o "$work/lib/libtalloc.so" "$src/talloc.o" "$src/replace.o" "$src/closefrom.o" \
    -Wl,-soname,libproot-talloc.so -Wl,-z,max-page-size=16384 -Wl,--no-undefined
}

# --- libandroid-shmem ---
# build_shmem WORK CC AR: WORK/lib/libandroid-shmem.a and WORK/include/sys/shm.h.
build_shmem() {
  local work=$1 cc=$2 ar=$3 src=$1/libandroid-shmem
  rm -rf "$src"
  mkdir -p "$src" "$work/lib" "$work/include/sys"
  tar -xzf "$DL/libandroid-shmem-$SHMEM_VERSION.tar.gz" -C "$src" --strip-components=1
  # A keyed segment is published as a symlink under _PATH_TMP. Bionic's
  # <paths.h> has none; this is the value termux-packages' sysroot gives it.
  $cc -O2 -fPIC -std=c11 -Wall -Wextra '-D_PATH_TMP="/data/data/com.termux/files/usr/tmp/"' \
    -c "$src/shmem.c" -o "$src/shmem.o"
  rm -f "$work/lib/libandroid-shmem.a"
  $ar rcs "$work/lib/libandroid-shmem.a" "$src/shmem.o"
  cp "$src/shm.h" "$work/include/sys/shm.h"
}

# --- proot ---
# build_proot WORK CC BIN [android]: WORK/proot/src/{proot,loader/loader}.
build_proot() {
  local work=$1 cc=$2 bin=$3 android=${4:-} src=$1/proot p
  rm -rf "$src" "$work/proot-${PROOT_TAG#v}"
  unzip -q "$DL/proot-$PROOT_TAG.zip" -d "$work"
  mv "$work/proot-${PROOT_TAG#v}" "$src"
  patch -d "$src" -p1 --fuzz=0 --no-backup-if-mismatch --quiet < "$DL/include-string.patch"
  while read -r p; do
    [ -n "$p" ] || continue
    patch -d "$src" -p1 --fuzz=0 --no-backup-if-mismatch --quiet < "$PATCHES/$p" \
      || { echo "$p does not apply to proot $PROOT_TAG" >&2; exit 1; }
  done < "$PATCHES/series"

  # The makefile appends to CPPFLAGS, CFLAGS and LDFLAGS, so they come from the
  # environment; a command-line value would replace its own. $ORIGIN is escaped
  # for make and then for the shell.
  local cppflags="-I$work/talloc -I$work/include"
  local ldflags="-L$work/lib -Wl,-rpath,\\\$\$ORIGIN -Wl,--as-needed"
  local make_args=()
  if [ -n "$android" ]; then
    cppflags="$cppflags -DARG_MAX=131072"
    ldflags="$ldflags -llog -landroid"
    make_args+=(PROOT_WITH_LIBANDROID_SHMEM=true)
  fi
  # LD links both proot and the loader, whose flags the makefile sets itself.
  # The patched source runs the loader PROOT_UNBUNDLE_LOADER names from beside
  # itself. HAS_LOADER_32BIT is emptied so no 32-bit loader is built. GIT=false
  # keeps `git describe` from naming whatever repository encloses the tree.
  CPPFLAGS=$cppflags LDFLAGS=$ldflags make -C "$src/src" -j"$(nproc)" \
      CC="$cc" LD="$cc -Wl,-z,max-page-size=16384" \
      OBJCOPY="$bin/objcopy" OBJDUMP="$bin/objdump" STRIP="$bin/strip" \
      PROOT_UNBUNDLE_LOADER=libproot-loader.so HAS_LOADER_32BIT= GIT=false \
      "${make_args[@]}" proot loader/loader >"$work/proot.log" 2>&1 \
    || { tail -20 "$work/proot.log" >&2; echo "proot build failed; see $work/proot.log" >&2; exit 1; }
}

# stage WORK OUT STRIP: the three files into OUT under their packaged names.
stage() {
  local work=$1 out=$2 strip=$3
  mkdir -p "$out"
  cp "$work/proot/src/proot" "$out/libproot.so"
  cp "$work/proot/src/loader/loader" "$out/libproot-loader.so"
  cp "$work/lib/libtalloc.so" "$out/libproot-talloc.so"
  "$strip" --strip-unneeded "$out/libproot.so" "$out/libproot-talloc.so"
  "$strip" --strip-all "$out/libproot-loader.so"
  chmod 755 "$out/libproot.so" "$out/libproot-loader.so"
}

# verify DIR READELF: the linkage the packaged names depend on.
verify() {
  local dir=$1 readelf=$2 dynamic
  dynamic=$("$readelf" -d "$dir/libproot.so")
  grep -q 'NEEDED.*\[libproot-talloc.so\]' <<<"$dynamic" \
    || { echo "libproot.so does not need libproot-talloc.so" >&2; exit 1; }
  grep -q 'RUNPATH.*\[\$ORIGIN\]' <<<"$dynamic" \
    || { echo "libproot.so has no DT_RUNPATH \$ORIGIN" >&2; exit 1; }
  grep -q 'SONAME.*\[libproot-talloc.so\]' <<<"$("$readelf" -d "$dir/libproot-talloc.so")" \
    || { echo "libproot-talloc.so has another soname" >&2; exit 1; }
  grep -q 'Type: *EXEC' <<<"$("$readelf" -h "$dir/libproot-loader.so")" \
    || { echo "libproot-loader.so is not an EXEC" >&2; exit 1; }
}

if [ -n "$HOST_OUT" ]; then
  work=$CACHE/host
  mkdir -p "$work"
  cc=${CC:-cc}
  info "building proot for this machine"
  # The ashmem requests proot's ashmem_memfd extension names. The header is
  # Android's; a glibc host has none.
  mkdir -p "$work/include/linux"
  cat > "$work/include/linux/ashmem.h" <<'EOF'
#include <linux/ioctl.h>
#define ASHMEM_SET_SIZE _IOW(0x77, 3, size_t)
#define ASHMEM_GET_SIZE _IO(0x77, 4)
EOF
  build_talloc "$work" "$cc" "${AR:-ar}"
  build_proot "$work" "$cc" "$(dirname "$(command -v objcopy)")"
  stage "$work" "$HOST_OUT" strip
  verify "$HOST_OUT" readelf
  info "host proot: $HOST_OUT"
  exit 0
fi

find_ndk
for abi in "${ABIS[@]}"; do
  case $abi in
    arm64-v8a) triple=aarch64-linux-android ;;
    x86_64)    triple=x86_64-linux-android ;;
    *) echo "no proot for $abi" >&2; exit 2 ;;
  esac
  info "building proot for $abi"
  work=$CACHE/$abi
  mkdir -p "$work"
  # Some makefile steps name the tools without the llvm- prefix.
  bin=$work/bin
  mkdir -p "$bin"
  for t in objcopy objdump strip; do ln -sf "$TOOLCHAIN/bin/llvm-$t" "$bin/$t"; done
  cc=$TOOLCHAIN/bin/$triple$API-clang
  build_talloc "$work" "$cc" "$TOOLCHAIN/bin/llvm-ar" cross
  build_shmem "$work" "$cc" "$TOOLCHAIN/bin/llvm-ar"
  build_proot "$work" "$cc" "$bin" android

  out=$ROOT/build/proot/$abi
  stage "$work" "$out" "$TOOLCHAIN/bin/llvm-strip"
  verify "$out" "$TOOLCHAIN/bin/llvm-readelf"

  dest=$ROOT/app/src/main/jniLibs/$abi
  mkdir -p "$dest"
  for f in libproot.so libproot-talloc.so libproot-loader.so; do
    cp "$out/$f" "$dest/$f"
    chmod 644 "$dest/$f"
    printf '== %s: %s (%s bytes)\n' "$abi" "$dest/$f" "$(stat -c%s "$dest/$f")"
  done
done
