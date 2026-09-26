#!/system/bin/sh
# The shell the agent is handed, which is the userland's bash.
#
# Do not edit: the app rewrites this file on every start, and keeps a copy that
# differed from the shipped one as shell.sh.bak. To hand the agent another
# shell, set SHELL in launcher.local.sh.
#
# The preload is the exec router, for bash and everything it starts: it is how a
# program in the data directory runs at all and how `#!/usr/bin/env` resolves on
# a device with no /usr. The agent's environment carries none, since the agent is
# musl, so it is named here by the variable the app sets.

export PREFIX="$HARNESS_PREFIX"
export TERMUX__PREFIX="$HARNESS_PREFIX"
export TERMUX__ROOTFS="$HARNESS_ROOTFS"
export TERMUX_APP__DATA_DIR="$HARNESS_DATA"
export PATH="$HARNESS_PREFIX/bin:$PATH"
export LD_PRELOAD="$HARNESS_TERMUX_EXEC"

exec "$HARNESS_PREFIX/bin/bash" "$@"
