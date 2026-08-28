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

# --continue reopens the most recent conversation in this directory, which is the
# one the app resolves for a tab it opens itself. With nothing to continue it
# exits, so it is passed only once a transcript exists. Arguments given on the
# command line skip the guard and reach the agent as they are.
if [ "$#" -eq 0 ]; then
    # The agent files a project under its working directory with '/', '\', '.'
    # and ':' folded to '-', and names each transcript for the conversation it
    # holds. That directory also holds memory and a subdirectory per session, so
    # a *.jsonl in it is the only thing that says there is a conversation to
    # continue. The folding is ${var//pat/rep}, a ksh extension that Android's
    # /system/bin/sh has because it is mksh.
    project=$(pwd)
    project=${project//\//-}
    project=${project//\\/-}
    project=${project//./-}
    project=${project//:/-}
    # An unmatched glob comes back as the pattern itself, so the test is whether
    # the first word names a file. Taking the positional parameters is safe:
    # this branch is reached only when there are none.
    set -- "$HOME/.claude/projects/$project"/*.jsonl
    if [ -e "$1" ]; then
        set -- --continue
    else
        set --
    fi
fi

if [ -n "$HARNESS_SHIM" ]; then
    exec "$HARNESS_SHIM" "$HARNESS_LOADER" "$HARNESS_AGENT" --ide "$@"
fi
exec "$HARNESS_LOADER" "$HARNESS_AGENT" --ide "$@"
