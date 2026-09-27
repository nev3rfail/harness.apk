#!/usr/bin/env bash
# Fetch into this directory, as <level>/, the bionic files the trapped-call
# list is computed from, for API levels 30 to 36: libc/SYSCALLS.TXT, the
# libc/SECCOMP_*_COMMON.TXT and SECCOMP_*_APP.TXT lists, and the kernel headers
# libc/seccomp/gen_syscall_nrs*.cpp include for arm64 and x86_64, with the
# libc/ prefix dropped.
#
# Usage: fetch.sh [BIONIC]    BIONIC is a clone of platform/bionic; without it,
#                             a clone without blobs is made in a temporary
#                             directory
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
url=https://android.googlesource.com/platform/bionic

# level tag commit
releases="
30 android-11.0.0_r1 050b1b17b292418ca009ff0e07f25d1c036db246
31 android-12.0.0_r1 e1c42ccac8c56db870a352e234250445bdf7c6c5
32 android-12.1.0_r1 11fa611f2c52e17e23f679869724278e2becc862
33 android-13.0.0_r1 0b981d1ec055efaef86d8a5d402cf1b3167ba10b
34 android-14.0.0_r1 e4df46f213d8d363017dce7ded5d1f2159614425
35 android-15.0.0_r1 361ba86734fb2821a6adcfdf775db8abd04e0de0
36 android-16.0.0_r1 09a271af557444c9a6b3f3146d6d474156fd6cdb
"

headers="
libc/kernel/uapi/asm-arm64/asm/bitsperlong.h
libc/kernel/uapi/asm-arm64/asm/unistd.h
libc/kernel/uapi/asm-arm64/asm/unistd_64.h
libc/kernel/uapi/asm-generic/bitsperlong.h
libc/kernel/uapi/asm-generic/unistd.h
libc/kernel/uapi/asm-x86/asm/unistd_64.h
"

bionic=${1:-}
if [ -z "$bionic" ]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  bionic=$tmp/bionic
  git clone -q --filter=blob:none --no-checkout "$url" "$bionic"
fi

while read -r level tag commit; do
  [ -n "$level" ] || continue
  git -C "$bionic" fetch -q "$url" "refs/tags/$tag" 2>/dev/null || true
  if [ "$(git -C "$bionic" rev-parse "$tag^{commit}")" != "$commit" ]; then
    echo "$tag is not $commit" >&2
    exit 1
  fi
  paths=$(git -C "$bionic" ls-tree --name-only "$commit" libc/ \
    | grep -E '^libc/(SYSCALLS\.TXT|SECCOMP_[A-Z]+_(COMMON|APP)\.TXT)$')
  for h in $headers; do
    if git -C "$bionic" cat-file -e "$commit:$h" 2>/dev/null; then
      paths="$paths $h"
    fi
  done
  rm -rf "${here:?}/$level"
  mkdir -p "$here/$level"
  # shellcheck disable=SC2086
  git -C "$bionic" archive "$commit" $paths | tar -x -C "$here/$level" --strip-components=1
  echo "$level: $tag"
done <<<"$releases"
