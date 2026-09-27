#!/bin/sh
# Host tests for librelocate and repack-deb, with the host C compiler (Linux,
# x86_64).
#
# repack-deb needs dpkg-deb on PATH, and the lock tests need flock(1). SH names
# the shell repack-deb runs under: dash by default when there is one, since that
# is the userland's /bin/sh.
#
# Usage: run.sh
set -u

here=$(cd "$(dirname "$0")" && pwd)
cpp=$(dirname "$here")
shim=$(cd "$cpp/../assets" && pwd)/repack-deb
: "${SH:=$(command -v dash || echo sh)}"
CC=${CC:-gcc}

W=$(mktemp -d /tmp/rl.XXXXXX)
trap 'chmod -R u+rwx "$W" 2>/dev/null; rm -rf "$W"' EXIT

$CC -std=gnu11 -O1 -g -Wall -Wextra -Werror "$cpp/relocate.c" -o "$W/librelocate.so" || exit 1
$CC -std=gnu11 -O1 -Wall -Wextra -Werror "$here/elfedit.c" -o "$W/elfedit" || exit 1
R=$W/librelocate.so

pass=0
fail=0
ok() {
  if eval "$2"; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL: $1"; fi
}
now() { date +%s%N | cut -c1-13; }
tls_align() { readelf -lW "$1" | awk '$1 == "TLS" { print $NF }'; }
pad() { p=$1; while [ ${#p} -lt ${#TF} ]; do p="$p/"; done; echo "$p"; }

T=/data/data/com.termux
TF=$T/files
TC=$T/cache
N=/data/data/apk.harness
PAIRS="$TF $N/root $TC $N/cach $T $N"

# An executable whose PT_TLS alignment is raised past its TLS sections', as
# termux-elf-cleaner leaves one; a shared object like it with no PT_INTERP.
printf '__thread int v = 7;\nint main(void) { return v - 7; }\n' > "$W/tls.c"
$CC -O1 -o "$W/tls" "$W/tls.c" && "$W/elfedit" "$W/tls" tls-align 64 || exit 1
$CC -O1 -shared -fPIC -o "$W/tls.so" "$W/tls.c" && "$W/elfedit" "$W/tls.so" tls-align 64 || exit 1

# The busy-lock case waits out the full 30 s, so it runs beside everything else.
flock "$W/busy" sleep 40 &
busy_holder=$!
( sleep 0.5; mkdir -p "$W/empty"; "$R" --lock "$W/busy" --links "$W/empty" $PAIRS >/dev/null 2>&1
  echo $? > "$W/busy.status" ) &
busy_run=$!

# --- librelocate: content --------------------------------------------------

t=$W/content
mkdir -p "$t"
printf '%s\n' "$TF/usr/lib" "$TC/apt" "$T/x" > "$t/f"
ln -s "$TF/usr/lib" "$t/link"
"$R" --content "$t" $PAIRS > /dev/null
ok "content: every pair that fits" \
  '[ "$(cat "$t/f")" = "$(printf "%s\n" "$N/root/usr/lib" "$N/cach/apt" "$T/x")" ]'
ok "content: a symlink is left to --links" '[ "$(readlink "$t/link")" = "$TF/usr/lib" ]'

t=$W/padded
mkdir -p "$t"
echo "$TF/usr/lib" > "$t/f"
"$R" --content "$t" "$TF" /x/root > /dev/null
ok "content: a shorter replacement is padded with slashes" \
  '[ "$(cat "$t/f")" = "$(pad /x/root)/usr/lib" ]'

# --- librelocate: links ----------------------------------------------------

t=$W/links
mkdir -p "$t/d"
echo "$TF/usr" > "$t/file"
ln -s "$TF/usr/lib/a" "$t/files"
ln -s "$TF" "$t/exact"
ln -s "$TC/apt" "$t/d/cache"
ln -s "$T/other" "$t/d/bare"
ln -s "$T/filesystem/x" "$t/d/sibling"
ln -s /data/data/com.termuxx/a "$t/d/longer"
ln -s "/usr$TF" "$t/d/inside"
ln -s ../lib/a "$t/d/relative"
"$R" --links "$t" $PAIRS > /dev/null
ok "links: files prefix" '[ "$(readlink "$t/files")" = "$N/root/usr/lib/a" ]'
ok "links: the whole target" '[ "$(readlink "$t/exact")" = "$N/root" ]'
ok "links: cache prefix" '[ "$(readlink "$t/d/cache")" = "$N/cach/apt" ]'
ok "links: the bare data directory, whatever its length" \
  '[ "$(readlink "$t/d/bare")" = "$N/other" ]'
ok "links: a sibling of files takes the bare pair" \
  '[ "$(readlink "$t/d/sibling")" = "$N/filesystem/x" ]'
ok "links: no match inside a component" \
  '[ "$(readlink "$t/d/longer")" = /data/data/com.termuxx/a ]'
ok "links: no match past the start" '[ "$(readlink "$t/d/inside")" = "/usr$TF" ]'
ok "links: a relative target" '[ "$(readlink "$t/d/relative")" = ../lib/a ]'
ok "links: contents are left to --content" '[ "$(cat "$t/file")" = "$TF/usr" ]'

t=$W/links-short
mkdir -p "$t"
ln -s "$TF/usr/lib" "$t/l"
"$R" --links "$t" "$TF" /x/root > /dev/null
ok "links: no padding" '[ "$(readlink "$t/l")" = /x/root/usr/lib ]'

# --- librelocate: TLS ------------------------------------------------------

t=$W/tlstree
mkdir -p "$t"
cp "$W/tls" "$t/exe" && chmod 750 "$t/exe"
cp "$W/tls" "$t/ro" && chmod 555 "$t/ro"
cp "$W/tls" "$t/foreign" && "$W/elfedit" "$t/foreign" machine 183
cp "$W/tls" "$t/nosect" && "$W/elfedit" "$t/nosect" no-sections
cp "$W/tls.so" "$t/lib.so"
inode=$(stat -c %i "$t/exe")
"$R" --tls "$t" > /dev/null
ok "tls: set back to the TLS sections' alignment" '[ "$(tls_align "$t/exe")" = 0x4 ]'
ok "tls: through a new file" '[ "$(stat -c %i "$t/exe")" != "$inode" ]'
ok "tls: mode kept" '[ "$(stat -c %a "$t/exe")" = 750 ] && [ "$(stat -c %a "$t/ro")" = 555 ]'
ok "tls: a read-only file too" '[ "$(tls_align "$t/ro")" = 0x4 ]'
ok "tls: the program still runs" '"$t/exe"'
ok "tls: another machine left alone" '[ "$(tls_align "$t/foreign")" = 0x40 ]'
ok "tls: no section headers left alone" '[ "$(tls_align "$t/nosect")" = 0x40 ]'
ok "tls: no PT_INTERP left alone" '[ "$(tls_align "$t/lib.so")" = 0x40 ]'
ok "tls: nothing left beside it" '[ -z "$(ls "$t" | grep -v -e "^exe$" -e "^ro$" -e "^foreign$" -e "^nosect$" -e "^lib.so$")" ]'

# --- librelocate: md5sums --------------------------------------------------

m=$W/md5
mkdir -p "$m/usr/bin" "$m/info"
cp "$W/tls" "$m/usr/bin/tls"
key=$(realpath "$m/usr/bin/tls" | cut -c2-)
printf '%s  %s\n' "$(md5sum < "$m/usr/bin/tls" | cut -c1-32)" "$key" \
  0123456789abcdef0123456789abcdef other/file > "$m/info/p.md5sums"
"$R" --tls --md5sums "$m/info" "$m/usr" > /dev/null
ok "md5sums: entry for a TLS change rewritten" \
  '[ "$(head -1 "$m/info/p.md5sums")" = "$(md5sum < "$m/usr/bin/tls" | cut -c1-32)  $key" ]'
ok "md5sums: other entries kept" \
  '[ "$(tail -1 "$m/info/p.md5sums")" = "0123456789abcdef0123456789abcdef  other/file" ]'

# --- librelocate: stamp ----------------------------------------------------

t=$W/stamped
mkdir -p "$t"
ln -s "$TF/a" "$t/l"
echo v1 > "$W/stamp"
"$R" --stamp "$W/stamp" v1 --links "$t" $PAIRS > /dev/null
ok "stamp: current, nothing done" '[ $? -eq 0 ] && [ "$(readlink "$t/l")" = "$TF/a" ]'
echo v0 > "$W/stamp"
"$R" --stamp "$W/stamp" v1 --links "$t" $PAIRS > /dev/null
ok "stamp: older, done and written" \
  '[ "$(readlink "$t/l")" = "$N/root/a" ] && [ "$(cat "$W/stamp")" = v1 ]'
rm "$W/stamp"
cp "$W/tls" "$t/exe"
"$R" --stamp "$W/stamp" v1 --tls --md5sums "$W/missing" "$t" > /dev/null 2>&1
ok "stamp: a failed run writes none" '[ $? -eq 1 ] && [ ! -e "$W/stamp" ]'

# --- librelocate: locks and usage ------------------------------------------

( exec 7>>"$W/lk"; "$R" --flock 7 && ! flock -n "$W/lk" true )
ok "flock: held while the caller holds the descriptor" '[ $? -eq 0 ]'
ok "flock: released with it" 'flock -n "$W/lk" true'

flock "$W/lk2" sleep 2 &
holder=$!
sleep 0.3
t0=$(now)
"$R" --lock "$W/lk2" --links "$W/empty" $PAIRS > /dev/null
status=$?
t1=$(now)
ok "lock: waits for a holder, then runs" '[ $status -eq 0 ] && [ $((t1 - t0)) -ge 1400 ]'
wait $holder

"$R" "$W/empty" > /dev/null 2>&1
ok "usage: no mode" '[ $? -eq 2 ]'
"$R" --links "$W/empty" "$TF" > /dev/null 2>&1
ok "usage: a pair with one side" '[ $? -eq 2 ]'

# --- repack-deb ------------------------------------------------------------

A=$W/a
RF=$A/root
P=$RF/usr
LOCK=$P/tmp/.repack-deb-lock
mkdir -p "$P/bin" "$P/libexec" "$P/tmp" "$RF/applib" "$W/out"
cp "$R" "$RF/applib/librelocate.so"
cp "$shim" "$P/libexec/repack-deb"
cat > "$P/bin/dpkg" <<'EOF'
#!/bin/sh
echo "start $(date +%s%N | cut -c1-13) $*" >> "$FAKE_LOG"
for fd in /proc/$$/fd/*; do
  case $(readlink "$fd") in *.repack-deb-lock) echo holds-lock >> "$FAKE_LOG" ;; esac
done
for a; do
  case $a in *.deb) cp "$a" "$FAKE_OUT/" ;; esac
  if [ -d "$a" ]; then cp "$a"/*.deb "$FAKE_OUT/"; fi
done
[ -z "${FAKE_STATUS:-}" ] || echo "$FAKE_STATUS" >&9
sleep "${FAKE_SLEEP:-0}"
echo "end $(date +%s%N | cut -c1-13)" >> "$FAKE_LOG"
EOF
chmod 755 "$P/bin/dpkg"

mkdeb() {
  s=$W/src-$1
  u=$s$TF/usr
  mkdir -p "$s/DEBIAN" "$u/bin" "$u/lib"
  printf 'Package: %s\nVersion: 1\nArchitecture: all\nMaintainer: t <t@t>\nDescription: t\n' \
    "$1" > "$s/DEBIAN/control"
  printf '#!%s/bin/sh\n' "$TF/usr" > "$u/bin/script"
  chmod 755 "$u/bin/script"
  cp "$W/tls" "$u/bin/tls"
  ln -s "$TF/usr/lib/real" "$u/lib/l"
  ln -s "$TC/apt" "$u/lib/c"
  ln -s ../lib/real "$u/lib/rel"
  ( cd "$s" && find . -path ./DEBIAN -prune -o -type f -print | sed 's|^\./||' | sort \
      | xargs md5sum ) > "$s/DEBIAN/md5sums"
  dpkg-deb --root-owner-group -b -Znone "$s" "$2" > /dev/null
}
mkdeb x "$W/x.deb"
mkdeb y "$W/y.deb"

rd() {
  env -u PREFIX FAKE_LOG="$W/log" FAKE_OUT="$W/out" "$SH" "$P/libexec/repack-deb" "$@"
}
repack_dirs() { ls -d "$P"/tmp/repack-deb.* 2>/dev/null; }

mkdir -p "$P/tmp/repack-deb.stale1/sub"

# A run that names no archive, while another holds the lock.
: > "$W/log"
flock "$LOCK" sleep 3 &
holder=$!
sleep 0.3
t0=$(now)
rd --print-foreign-architectures
t1=$(now)
ok "no archive: no lock taken" '[ $((t1 - t0)) -lt 1000 ]'
ok "no archive: dpkg called" 'grep -q -- "--print-foreign-architectures" "$W/log"'
ok "no archive: no directory created" '[ "$(repack_dirs)" = "$P/tmp/repack-deb.stale1" ]'

# A run started during another waits for it.
t0=$(now)
rd --unpack "$W/x.deb"
status=$?
t1=$(now)
wait $holder
ok "repack: waits for the lock" '[ $status -eq 0 ] && [ $((t1 - t0)) -ge 2000 ]'
ok "repack: stale directories removed, its own too" '[ -z "$(repack_dirs)" ]'
ok "repack: dpkg does not hold the lock" '! grep -q holds-lock "$W/log"'

x=$W/x1
dpkg-deb -R "$W/out/x.deb" "$x"
u=$x$P
ok "repack: gzip" 'ar t "$W/out/x.deb" | grep -qx data.tar.gz'
ok "repack: payload moved under the rootfs" '[ -f "$u/bin/tls" ] && [ ! -e "$x$T" ]'
ok "repack: contents" '[ "$(cat "$u/bin/script")" = "#!$(pad "$RF")/usr/bin/sh" ]'
ok "repack: symlinks" \
  '[ "$(readlink "$u/lib/l")" = "$P/lib/real" ] && [ "$(readlink "$u/lib/c")" = "$A/cache/apt" ] &&
   [ "$(readlink "$u/lib/rel")" = ../lib/real ]'
ok "repack: TLS" '[ "$(tls_align "$u/bin/tls")" = 0x4 ]'
ok "repack: md5sums" '( cd "$x" && md5sum -c --quiet DEBIAN/md5sums )'

# apt's own descriptors reach dpkg intact, and the lock takes one below them.
: > "$W/log"
FAKE_STATUS=status-line rd --status-fd 9 --unpack "$W/x.deb" 9> "$W/status"
ok "status-fd 9: dpkg writes through it" '[ $? -eq 0 ] && [ "$(cat "$W/status")" = status-line ]'
ok "status-fd 9: dpkg does not hold the lock" '! grep -q holds-lock "$W/log"'

rd --unpack "$W/x.deb" 3>/dev/null 4>/dev/null 5>/dev/null 6>/dev/null 7>/dev/null \
  8>/dev/null 9>/dev/null 2> "$W/err"
ok "no free descriptor: a failure with a message" '[ $? -ne 0 ] && grep -q descriptor "$W/err"'

# The first run's cleanup is behind it, and its dpkg runs with the lock closed,
# yet the second still waits for the first to end.
: > "$W/log"
inode=$(stat -c %i "$LOCK")
FAKE_SLEEP=2 rd --unpack "$W/x.deb" &
first=$!
i=0
while ! grep -q '^start' "$W/log" && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
rd --unpack "$W/y.deb"
wait $first
first_end=$(grep '^end' "$W/log" | head -1 | cut -d' ' -f2)
second_start=$(grep '^start' "$W/log" | sed -n 2p | cut -d' ' -f2)
ok "concurrent: the second starts dpkg after the first ends" \
  '[ -n "$first_end" ] && [ -n "$second_start" ] && [ "$second_start" -ge "$first_end" ]'
ok "concurrent: one lock file throughout" '[ "$(stat -c %i "$LOCK")" = "$inode" ]'

# The directory after --recursive.
rm -f "$W/out"/*.deb
mkdir -p "$W/debs"
cp "$W/x.deb" "$W/y.deb" "$W/debs/"
rd --unpack --recursive "$W/debs"
ok "recursive: every archive repacked" \
  'ar t "$W/out/x.deb" | grep -qx data.tar.gz && ar t "$W/out/y.deb" | grep -qx data.tar.gz'

# --- librelocate: a lock that stays busy -----------------------------------

wait $busy_run
kill $busy_holder 2>/dev/null
ok "lock: busy for 30 s, a distinct status" '[ "$(cat "$W/busy.status")" = 75 ]'

echo "$pass passed, $fail failed"
[ $fail -eq 0 ]
