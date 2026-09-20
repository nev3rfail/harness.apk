#!/system/bin/sh
# Start the native chromium headless with a CDP endpoint on 127.0.0.1:9222.
# The profile lives beside this script, so the path follows wherever it is copied.
DIR=$(dirname "$0")

# This runs under Android's sh, which inherits whatever PATH the caller had.
# chromium-browser is under the userland prefix, so put it in reach when the
# environment names one.
[ -n "$PREFIX" ] && PATH="$PREFIX/bin:$PATH"

exec chromium-browser \
  --headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage \
  --remote-debugging-address=127.0.0.1 --remote-debugging-port=9222 \
  --user-data-dir="$DIR/profile" \
  --no-first-run --no-default-browser-check "$@"
