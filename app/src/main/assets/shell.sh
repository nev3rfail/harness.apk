#!/system/bin/sh
# The shell the agent is handed, which is the userland's bash.
#
# The indirection is what termux-exec costs. That library is how
# `#!/usr/bin/env` resolves on a device with no /usr, and it works by being
# preloaded into whatever runs the script -- but it is bionic, and the agent is
# musl, so preloading it into the agent fails to relocate against a libc with no
# __register_atfork. The preload can only be named on the far side of an exec,
# which means a file.

export PREFIX="$HARNESS_PREFIX"
export TERMUX__PREFIX="$HARNESS_PREFIX"
export TERMUX__ROOTFS="$HARNESS_ROOTFS"
export TERMUX_APP__DATA_DIR="$HARNESS_DATA"
export PATH="$HARNESS_PREFIX/bin:$PATH"
export LD_PRELOAD="$HARNESS_PREFIX/lib/libtermux-exec-ld-preload.so"

exec "$HARNESS_PREFIX/bin/bash" "$@"
