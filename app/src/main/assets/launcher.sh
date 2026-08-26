#!/system/bin/sh
# What the agent needs from the platform.
#
# Nothing here is spelled absolutely. The app exports every path it owns as a
# HARNESS_ variable, so one copy of this file serves every channel and the staged
# file is the same bytes as the asset it came from.
#
# Edited on a device, this survives a relaunch: the app stages it when it is
# absent and leaves it alone when it is there. Deleting it restores it.

export HOME="$HARNESS_HOME"
export TMPDIR="$HARNESS_TMP"

# Android answers a name lookup through netd rather than through a nameserver in
# /etc/resolv.conf, so a program carrying its own resolver has nothing to read.
# The tracer redirects /etc at this directory, and the app writes the file.
export SYSCALL_SHIM_ETC="$HARNESS_ETC"

# A URL is drawn as a hyperlink only for a terminal the agent believes supports
# them, and it recognises this one by nothing. This is ghostty's terminal,
# hyperlinks work, and a tap on one hands the URL to the phone. That is how a
# login is completed here: the agent cannot open a browser itself, so it prints
# the authorization URL and someone taps it.
export FORCE_HYPERLINK=1

# Android's own shell is toybox, and it is what the agent gets when no userland
# is installed. Every tool the agent builds on a shell is disabled without one.
export SHELL=/system/bin/sh

if [ -x "$HARNESS_PREFIX/bin/bash" ]; then
    export PREFIX="$HARNESS_PREFIX"
    export TERMUX__PREFIX="$HARNESS_PREFIX"
    export TERMUX__ROOTFS="$HARNESS_ROOTFS"
    export TERMUX_APP__DATA_DIR="$HARNESS_DATA"
    # Carried in the environment as well as in the wrapper, so the userland's
    # programs are found by whatever ran them.
    export PATH="$HARNESS_PREFIX/bin:$PATH"
    export SHELL="$HARNESS_HOME/shell.sh"
fi

exec "$HARNESS_HOME/agent.sh"
