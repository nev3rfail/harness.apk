#!/system/bin/sh
#
# Do not edit: the app rewrites this file on every install. Put anything of your
# own in launcher.local.sh beside it, which is sourced below and which nothing
# here writes.
#
# What the agent needs from the platform. Paths arrive as HARNESS_ variables, so
# one copy serves every channel.
#
# The session starts with the exec router in LD_PRELOAD, so every program this
# starts from the data directory is routed through a loader the platform lets
# run: a script by its shebang, a bionic program through linker64, the agent
# through the musl loader.

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

# The extension point, sourced with the environment assembled so it has the last
# word on any of it. Absent by default: nothing stages it, and its absence is how
# a device says it wants none.
[ -f "$HARNESS_HOME/launcher.local.sh" ] && . "$HARNESS_HOME/launcher.local.sh"

# A run with no terminal: nothing to keep open once the agent exits, so the
# launcher becomes the agent and exits with it. launcher.local.sh can name
# another headless script, as it can another HARNESS_AGENT_SCRIPT.
# HARNESS_AGENT_ARGS splits into arguments as a tab's does below, and set -f
# keeps the shell from also expanding them as globs.
if [ -n "$HARNESS_HEADLESS_SCRIPT" ]; then
    set -f
    exec "$HARNESS_HOME/$HARNESS_HEADLESS_SCRIPT" $HARNESS_AGENT_ARGS
fi

# Run the agent rather than become it: exec would take the pty down on its exit.
#
# HARNESS_AGENT_SCRIPT names the script that starts the agent, so a backend
# brings its own invocation and launcher.local.sh can name one of its own. It
# falls back to agent.sh for a caller that names none. The script is started by
# path and routed by its shebang.
#
# HARNESS_AGENT_ARGS is what the app asks for on behalf of a tab or a headless
# run -- which conversation to open, which id to give a new one, where its MCP
# config is, and for a headless run how it reads and writes.
# Unquoted on purpose, so it splits into arguments; nothing the app puts there
# carries a space. Empty for a session started outside the app, which leaves the
# script its own defaults.
"$HARNESS_HOME/${HARNESS_AGENT_SCRIPT:-agent.sh}" $HARNESS_AGENT_ARGS

# The session then belongs to a shell. The wrapper sets up the userland's
# environment, so it comes first; bash directly when the wrapper is gone, still
# routed by the session's preload; toybox with no userland.
if [ -x "$HARNESS_PREFIX/bin/bash" ]; then
    [ -x "$HARNESS_HOME/shell.sh" ] && exec "$HARNESS_HOME/shell.sh"
    exec "$HARNESS_PREFIX/bin/bash"
fi
exec /system/bin/sh
