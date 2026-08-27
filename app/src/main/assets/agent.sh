#!/system/bin/sh
# How the agent is started. This is the file to edit to change that.
#
# The loader runs as a program with the agent as its argument, so the download is
# stored exactly as it arrived and needs no execute bit. On x86_64 the tracer goes
# in front: Android's seccomp allowlist covers only the *at syscalls bionic uses,
# and the agent's runtime calls the legacy ones where the ABI still has them.
# aarch64 never had them, so HARNESS_SHIM is empty there.

# A shell that starts this carries the wrapper's preload, which is bionic and
# cannot relocate against the agent's musl.
unset LD_PRELOAD

# Bun resolves names through libc for fetch and c-ares for dns. c-ares finds no
# /etc/resolv.conf, falls back to a loopback nameserver nothing answers, and
# waits out its timeout; a preload names the resolvers instead.
export BUN_OPTIONS="--preload $HARNESS_ETC/setdns.js"

# The staged binary is the one the app downloaded and verified.
export DISABLE_AUTOUPDATER=1

# --resume with nothing to resume exits, so it is passed only with a transcript.
# Arguments take its place, which is how a shell asks for something else.
resume=
if [ -n "$(ls -A "$HOME/.claude/projects" 2>/dev/null)" ]; then
    resume=--resume
fi

[ "$#" -gt 0 ] || set -- $resume

if [ -n "$HARNESS_SHIM" ]; then
    exec "$HARNESS_SHIM" "$HARNESS_LOADER" "$HARNESS_AGENT" --ide "$@"
fi
exec "$HARNESS_LOADER" "$HARNESS_AGENT" --ide "$@"
