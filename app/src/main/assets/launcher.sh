#!/system/bin/sh
# What the agent needs from the platform. Paths arrive as HARNESS_ variables, so
# one copy serves every channel. The app stages this only when it is absent, so
# an edit here survives a relaunch and deleting it restores the original.

export HOME="$HARNESS_HOME"
export TMPDIR="$HARNESS_TMP"

# Android resolves names through netd, so a program carrying its own resolver
# finds no /etc/resolv.conf. The tracer redirects /etc here, and the app fills it.
export SYSCALL_SHIM_ETC="$HARNESS_ETC"

# The agent cannot open a browser, so a login is a URL it prints and someone
# taps. It draws one only for a terminal it believes does hyperlinks; this one does.
export FORCE_HYPERLINK=1

# Toybox: what there is to run without a userland.
export SHELL=/system/bin/sh

# The shell can start the agent too, through the same script this launcher uses.
mkdir -p "$HARNESS_HOME/bin"
ln -sf "$HARNESS_HOME/agent.sh" "$HARNESS_HOME/bin/claude"
export PATH="$HARNESS_HOME/bin:$PATH"

if [ -x "$HARNESS_PREFIX/bin/bash" ]; then
    export PREFIX="$HARNESS_PREFIX"
    export TERMUX__PREFIX="$HARNESS_PREFIX"
    export TERMUX__ROOTFS="$HARNESS_ROOTFS"
    export TERMUX_APP__DATA_DIR="$HARNESS_DATA"
    # In the environment as well as in the wrapper, so a parent finds these too.
    export PATH="$HARNESS_PREFIX/bin:$PATH"
    export SHELL="$HARNESS_HOME/shell.sh"
fi

# Run the agent rather than become it: exec would take the pty down on its exit.
"$HARNESS_HOME/agent.sh"

# The session then belongs to a shell. The wrapper carries termux-exec, so it
# comes first; bash directly when the wrapper is gone; toybox with no userland.
if [ -x "$HARNESS_PREFIX/bin/bash" ]; then
    [ -x "$HARNESS_HOME/shell.sh" ] && exec "$HARNESS_HOME/shell.sh"
    exec "$HARNESS_PREFIX/bin/bash"
fi
exec /system/bin/sh
