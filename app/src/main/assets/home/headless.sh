#!/system/bin/sh
# How the agent is started with no terminal, for the panel over another app.
#
# Do not edit: the app rewrites this file on every install. To start it another
# way, set HARNESS_HEADLESS_SCRIPT in launcher.local.sh to a script of your own.
#
# No --ide: the home tab's editor advertises the home, and the panel's directory
# lies under it. No --continue guard: the panel always names its conversation.

# A shell that starts this carries the wrapper's preload, which is bionic and
# cannot relocate against the agent's musl.
unset LD_PRELOAD

# As in agent.sh: c-ares finds no /etc/resolv.conf, so a preload names the resolvers.
export BUN_OPTIONS="--preload $HARNESS_ETC/setdns.js"

# The staged binary is the one the app downloaded and verified.
export DISABLE_AUTOUPDATER=1

# The app reads this process's pid from the first stderr line: exec keeps the
# pid, so it is the pid of the process the app started, the tracer on x86_64.
echo "harness-pid $$" >&2

# The arguments are positional by now; the agent's own children need neither.
unset HARNESS_HEADLESS_SCRIPT HARNESS_AGENT_ARGS

# As in agent.sh, the tracer goes in front on x86_64 and is empty elsewhere.
if [ -n "$HARNESS_SHIM" ]; then
    exec "$HARNESS_SHIM" "$HARNESS_LOADER" "$HARNESS_AGENT" "$@"
fi
exec "$HARNESS_LOADER" "$HARNESS_AGENT" "$@"
