#!/bin/sh
# Build and run the router's host unit tests with the host C compiler (Linux,
# x86_64 or aarch64).
#
# Usage: run.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/harness-exec-test

"${CC:-gcc}" -std=gnu11 -O1 -g -Wall -Wextra -Werror \
  "$here/harness-exec-test.c" -o "$out"
"$out"
