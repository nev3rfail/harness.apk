#!/bin/bash
# End-to-end check of the patched musl on an x86_64 Linux host, against the tree
# scripts/stage-claude.sh built: a musl program started through the loader
# starts another through each routed call, and the child must see itself, not
# the loader. The shim is a stand-in built from the router.
#
# Usage: musl-e2e.sh [MUSL_CACHE]      defaults to stage-claude.sh's cache
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
cache=${1:-${MUSL_CACHE:-${TMPDIR:-/tmp}/harness-musl}}
src=$cache/musl-1.2.5
build=$cache/build-x86_64
[ "$(uname -m)" = x86_64 ] || { echo "needs an x86_64 host" >&2; exit 2; }
[ -f "$build/lib/libc.so" ] || { echo "no musl build in $build; run stage-claude.sh first" >&2; exit 2; }

make -s -C "$build" lib/Scrt1.o lib/crti.o lib/crtn.o

E=$(mktemp -d)
trap 'rm -rf "$E"' EXIT
mkdir -p "$E/lib" "$E/data" "$E/etc" "$E/prefix/bin"
cp "$build/lib/libc.so" "$E/lib/libmuslloader.so"
gcc -O1 -Wall -Werror "$here/fake-shim.c" "$here/../harness-exec.c" -o "$E/lib/libsyscallshim.so"
echo 'void harness_exec_placeholder(void) {}' | gcc -shared -fPIC -x c - -o "$E/lib/libtermuxexec.so"
gcc -O1 -Wall -Werror -U_FORTIFY_SOURCE -fno-stack-protector -nostdinc \
  -I"$src/arch/x86_64" -I"$src/arch/generic" -I"$build/obj/include" -I"$src/include" \
  -c "$here/probe.c" -o "$E/probe.o"
gcc -nostdlib -pie -Wl,--dynamic-linker=/lib/ld-musl-x86_64.so.1 \
  "$build/lib/Scrt1.o" "$build/lib/crti.o" "$E/probe.o" "$build/lib/libc.so" "$build/lib/crtn.o" \
  -o "$E/data/probe"
printf '127.0.0.9 hxhost\n' > "$E/etc/hosts"
printf 'nameserver 127.0.0.1\n' > "$E/etc/resolv.conf"

LD=$E/lib/libmuslloader.so
P=$E/data/probe
failures=0

run() {
  env -i PATH="$E/data:/usr/bin:/bin" HARNESS_PREFIX="$E/prefix" HARNESS_ETC="$E/etc" "$@"
}

check() {
  local name=$1 expected=$2 actual
  shift 2
  actual=$(run "$@" 2>&1) || true
  if [ "$actual" = "$expected" ]; then
    echo "ok    $name"
  else
    echo "FAIL  $name"
    echo "        expected: $expected"
    echo "        actual:   $actual"
    failures=$((failures + 1))
  fi
}

seen="argv0=rg readlink=$P realpath=$P pse=$P preload= prefix=$E/prefix"
check "posix_spawn"                "$seen" "$LD" "$P" spawn "$P" rg exe
check "posix_spawnp"               "$seen" "$LD" "$P" spawnp probe rg exe
check "posix_spawn, empty envp"    "$seen" "$LD" "$P" spawnenv "$P" rg exe
check "posix_spawn, addchdir"      "$seen" "$LD" "$P" spawndir "$E/data" ./probe rg exe
check "execv"                      "$seen" "$LD" "$P" exec "$P" rg exe
check "execvp"                     "$seen" "$LD" "$P" execvp probe rg exe
check "execv /proc/self/exe"       "$seen" env TERMUX_EXEC__PROC_SELF_EXE="$P" "$LD" "$P" exec /proc/self/exe rg exe
check "readlink, started by name"  "argv0=$P readlink=$P realpath=$P pse=$P preload= prefix=$E/prefix" \
  env TERMUX_EXEC__PROC_SELF_EXE="$P" "$LD" "$P" exe
check "readlink, no name to give"  "argv0=$P readlink=$LD realpath=$LD pse= preload= prefix=$E/prefix" \
  "$LD" "$P" exe
check "system"                     "ok" sh -c "'$LD' '$P' system 'exit 7'; [ \$? = 7 ] && echo ok"
check "/etc through HARNESS_ETC"   "127.0.0.9" "$LD" "$P" hosts hxhost
check "getifaddrs"                 "ok" sh -c "n=\$('$LD' '$P' ifaddrs); [ \"\$n\" -gt 0 ] && echo ok"

echo "$failures failures"
[ "$failures" = 0 ]
