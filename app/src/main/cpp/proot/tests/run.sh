#!/bin/sh
# Build proot for this machine with the packaged patches and run their host
# tests (Linux, x86_64 or aarch64). The host kernel's page size is the one the
# page-size checks see.
#
# Usage: run.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../../../../.." && pwd)
w=${TMPDIR:-/tmp}/proot-test
cc=${CC:-gcc}

rm -rf "$w"
mkdir -p "$w/xbin" "$w/rootfs/bin" "$w/alone"
bash "$root/scripts/build-proot.sh" --host "$w/proot"
P=$w/proot/libproot.so

"$cc" -O1 -Wall -Wextra -Werror "$here/probe.c" -o "$w/probe"
"$cc" -O1 -Wall -Wextra -Werror -DTAG='"xbin"' "$here/probe.c" -o "$w/xbin/hx-probe"
"$cc" -O1 -Wall -Wextra -Werror -static -DTAG='"guest"' "$here/probe.c" -o "$w/rootfs/bin/hx-probe"
"$cc" -O1 -Wall -Wextra -Werror "$here/mkelf.c" -o "$w/mkelf"
for m in good misaligned shared same class32; do
  "$w/mkelf" "$m" "$w/$m"
  chmod 755 "$w/$m"
done
# libandroid-shmem with its patch. Android's two headers it names are stubbed:
# the log call prints nothing, and the ashmem requests are never reached.
# Bionic declares open(2) and ioctl(2) through headers glibc keeps apart.
mkdir -p "$w/shmem/include/android" "$w/shmem/include/linux" "$w/tmp"
shmem_version=$(sed -n 's/^SHMEM_VERSION=//p' "$root/scripts/build-proot.sh")
tar -xzf "$root/build/proot/dl/libandroid-shmem-$shmem_version.tar.gz" -C "$w/shmem" --strip-components=1
patch -d "$w/shmem" -p1 --fuzz=0 --quiet < "$here/../shmem-tmp-dir.patch"
printf '#define ANDROID_LOG_INFO 4\n#define __android_log_print(...) 0\n' \
  > "$w/shmem/include/android/log.h"
printf '#include <linux/ioctl.h>\n#define ASHMEM_NAME_LEN 256\n#define ASHMEM_SET_NAME _IOW(0x77, 1, char[256])\n#define ASHMEM_SET_SIZE _IOW(0x77, 3, size_t)\n#define ASHMEM_GET_SIZE _IO(0x77, 4)\n' \
  > "$w/shmem/include/linux/ashmem.h"
"$cc" -O1 -D_GNU_SOURCE -include fcntl.h -include sys/ioctl.h -I"$w/shmem/include" "$w/shmem/shmem.c" "$here/shm-key.c" \
  -o "$w/shm-key" -lpthread
printf '#!/usr/bin/hx-probe -x\n' > "$w/script"
chmod 755 "$w/script"
cp "$P" "$w/proot/libproot-talloc.so" "$w/alone/"
interp=$(readelf -lW "$P" | sed -n 's/.*Requesting program interpreter: \(.*\)]/\1/p')

failed=0
# run COMMAND...: its output and its exit status, as one line.
run() {
  if out=$("$@" 2>&1); then rc=0; else rc=$?; fi
  printf '%s rc=%s' "$out" "$rc"
}
# check NAME EXPECTED OUTPUT: OUTPUT contains EXPECTED.
check() {
  case $3 in
    *"$2"*) echo "ok   $1" ;;
    *) echo "FAIL $1: expected '$2' in '$3'"; failed=$((failed + 1)) ;;
  esac
}

# P1
check "loader beside proot, PROOT_LOADER ignored" "tag=probe" \
  "$(run env PROOT_LOADER=/nonexistent PROOT_LOADER_32=/nonexistent "$P" "$w/probe")"
check "proot run through the dynamic linker" "tag=probe" \
  "$(cd "$w/proot" && run "$interp" ./libproot.so "$w/probe")"
check "proot run through the dynamic linker from another directory" "tag=probe" \
  "$(cd "$w" && run "$interp" proot/libproot.so "$w/probe")"
check "no loader beside proot" "execve(\"$w/probe\"): No such file or directory" \
  "$(run "$w/alone/libproot.so" "$w/probe")"
check "32-bit tracee" "ENOEXEC rc=1" "$(run "$P" "$w/probe" exec "$w/class32")"

# P2
check "--exec-bin, the command" "tag=xbin exe=$w/xbin/hx-probe" \
  "$(run "$P" --exec-bin "$w/xbin" /usr/bin/hx-probe)"
check "--exec-bin, a direct exec" "tag=xbin exe=$w/xbin/hx-probe [hx-probe] rc=0" \
  "$(run "$P" --exec-bin "$w/xbin" "$w/probe" exec /bin/hx-probe hx-probe)"
check "--exec-bin, a shebang" "tag=xbin exe=$w/xbin/hx-probe [$w/xbin/hx-probe] [-x] [$w/script] rc=0" \
  "$(run "$P" --exec-bin "$w/xbin" "$w/probe" exec "$w/script" script)"
check "--exec-bin, a file DIR lacks" "from-bin rc=0" \
  "$(run "$P" --exec-bin "$w/xbin" "$w/probe" exec /bin/sh sh -c 'echo from-bin')"
check "--exec-bin, a relative DIR" "expects an absolute path" \
  "$(cd "$w" && run "$P" --exec-bin xbin /usr/bin/hx-probe)"
check "no --exec-bin" "ENOENT rc=1" \
  "$(run "$P" "$w/probe" exec /bin/hx-probe hx-probe)"
check "--exec-bin under -r, the command" "tag=guest exe=/bin/hx-probe" \
  "$(run "$P" --exec-bin "$w/xbin" -r "$w/rootfs" -b /proc -w / /bin/hx-probe)"
check "--exec-bin under -r, a direct exec" "ENOENT rc=1" \
  "$(run "$P" --exec-bin "$w/xbin" -r "$w/rootfs" -b /proc -w / /bin/hx-probe exec /usr/bin/hx-probe x)"

# P5
check "aligned PT_LOAD" "rc=0" "$(run "$P" "$w/probe" exec "$w/good")"
check "PT_LOADs sharing a page with equal flags" "rc=0" "$(run "$P" "$w/probe" exec "$w/same")"
check "PT_LOAD off the page size" "ENOEXEC rc=1" "$(run "$P" "$w/probe" exec "$w/misaligned")"
check "PT_LOADs sharing a page with other flags" "ENOEXEC rc=1" "$(run "$P" "$w/probe" exec "$w/shared")"

# libandroid-shmem's key directory. timeout bounds each call.
check "shmem key, PROOT_TMP_DIR unset" "ret=-1 errno=ENOENT rc=0" \
  "$(run env -u PROOT_TMP_DIR timeout 10 "$w/shm-key")"
check "shmem key, PROOT_TMP_DIR missing" "ret=-1 errno=ENOENT rc=0" \
  "$(run env PROOT_TMP_DIR="$w/none" timeout 10 "$w/shm-key")"
env PROOT_TMP_DIR="$w/tmp" timeout 10 "$w/shm-key" >/dev/null 2>&1 || true
check "shmem key, published in PROOT_TMP_DIR" "link" \
  "$( [ -L "$w/tmp/ashv_key_4660" ] && echo link || echo none)"

if [ "$failed" -gt 0 ]; then
  echo "$failed failed"
  exit 1
fi
echo "all passed"
