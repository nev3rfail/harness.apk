#!/system/bin/sh
# How the agent is started. This is the file to edit to change that.
#
# The loader is run as a program with the agent as its argument. musl's dynamic
# linker supports that, so the downloaded binary is stored exactly as it arrived
# -- its checksum keeps describing it, and it needs no execute bit.
#
# On x86_64 the tracer goes in front. Android grants an app an allowlist of
# syscalls covering what bionic calls, which is only the *at variants, and
# answers the rest with SECCOMP_RET_KILL_PROCESS. The agent's runtime uses the
# legacy calls where the ABI still offers them. The aarch64 Linux ABI never had
# them, so there is nothing to translate and HARNESS_SHIM is empty there.

# The agent is a Bun program, and Bun resolves names two ways: libc for fetch,
# and its own c-ares for the dns module. c-ares reads the same absent
# /etc/resolv.conf and then falls back to a nameserver on loopback that nothing
# answers, so every lookup through it spends its full timeout before failing. A
# preload names the resolvers instead.
export BUN_OPTIONS="--preload $HARNESS_ETC/setdns.js"

# The staged binary is the one the app downloaded and verified.
export DISABLE_AUTOUPDATER=1

# --resume with nothing to resume prints "No conversations found to resume" and
# exits, so the flag is passed only once there is a transcript.
resume=
if [ -n "$(ls -A "$HOME/.claude/projects" 2>/dev/null)" ]; then
    resume=--resume
fi

if [ -n "$HARNESS_SHIM" ]; then
    exec "$HARNESS_SHIM" "$HARNESS_LOADER" "$HARNESS_AGENT" --ide $resume
fi
exec "$HARNESS_LOADER" "$HARNESS_AGENT" --ide $resume
