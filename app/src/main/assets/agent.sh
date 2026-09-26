#!/system/bin/sh
# How the agent is started.
#
# Do not edit: the app rewrites this file on every start, and keeps a copy that
# differed from the shipped one as agent.sh.bak. To start the agent another way,
# set HARNESS_AGENT_SCRIPT in launcher.local.sh to a script of your own.
#
# The loader runs from the native library directory with the agent as its
# argument. The exec router in the calling shell's preload sees the loader and
# puts the tracer in front on x86_64: Android's seccomp allowlist covers only the
# *at syscalls bionic uses, and the agent's runtime calls the legacy ones where
# the ABI still has them.

# The router's preload is bionic and cannot relocate against the agent's musl.
# The router in the loader's own libc takes over from here.
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

exec "$HARNESS_LOADER" "$HARNESS_AGENT" --ide "$@"
