# Yolo session ledger

Goal handed off: at minimum a running Claude Code inside the app's terminal; at
most a working MVP. Credentials may be copied from the host's
`~/.claude/.credentials.json`.

## Decisions

### D1 — musl builds instead of upstream's glibc shim
Upstream `claude-code-android` installs Termux's `glibc-runner` (~50 MB) and
patchelfs the `linux-arm64` binary to use its loader. Anthropic also publishes
`linux-x64-musl` / `linux-arm64-musl`. Read their ELF headers off the CDN by
range request:

    INTERP -> /lib/ld-musl-x86_64.so.1
    NEEDED: ['libc.musl-x86_64.so.1']

On musl the loader *is* libc and satisfies that single DT_NEEDED from itself, so
the entire port is one ~900 KB shared library plus a `--set-interpreter`. Chose
this over the glibc bundle: no Termux, no apt, no `$PREFIX`, and the same code
path works on both architectures.

### D2 — targetSdk 28
Measured, not assumed. Ran an exec of a copied binary from the app's `filesDir`
inside the app's own pty:

    targetSdk 35 -> rc=126, u:r:untrusted_app,    "Permission denied"
    targetSdk 28 -> rc=127, u:r:untrusted_app_27, exec succeeded

API 29+ drops `execute_no_trans` on `app_data_file`, which is why Termux still
targets 28. Approved by partner before the change.

The alternative was exec'ing the loader out of `nativeLibraryDir` and passing
claude as its argument (the binary is only mmap'd, so it needs `execute`, not
`execute_no_trans`). Rejected: every subprocess claude spawns would need the
same trick, and `/proc/self/exe` would report the loader.

### D3 — build musl from source rather than unpack Alpine's apk
Partner's call ("we can build musl, why not"). `configure && make lib/libc.so`
on the host compiler. Cross-compiling for arm64 takes a `CC` override; a
prebuilt is acceptable there if it fights back.

### D4 — musl tarball from musl.libc.org, pinned by sha256
`musl.libc.org` is intermittently unreachable from this box (curl 28/35 on some
attempts, HTTP 200 on others). Downloaded from the Windows side, which is
reliable here, and pinned:

    a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4

### D5 — downloads on Windows, build and patchelf in WSL
WSL's network on this box is flaky and its view of the repo is a 9p mount.
Windows has working network; WSL has `patchelf` and a Linux toolchain. So the
script tolerates pre-populated artifacts: a cached tarball, a cached
`manifest.json`, and a `VERSION` file all skip their network calls.

Corollary: invoke WSL through PowerShell. Git Bash's MSYS argument conversion
mangles the script passed to `bash -c`, silently emptying variables.

### D6 — the app needs INTERNET
The fork's manifest declared no permissions at all. Claude Code reported "Not
logged in" because its token refresh had no network. Added
`android.permission.INTERNET`.

### D7 — musl's legacy syscalls versus Android's seccomp allowlist
Under `run-as` the binary runs; started from inside the app it dies with
`Bad system call` (SIGSYS, exit 159). `run-as` is a different SELinux domain
with a different filter, so it is not a valid test of what the app can do.

The policy uses `SECCOMP_RET_KILL_PROCESS`, so there is no signal stop to
inspect, and a musl-linked process has no bionic handler to name the syscall.
Two probes:

- a ptrace tracer reporting the last syscalls attempted. It showed that a freshly
  cloned thread died, but the kill takes down the whole thread group, so the
  offender's own entry stop was never seen. Discarded once it had served.
- `tools/seccomp-scan.c` settles it by running every syscall number in its own
  forked child and recording which die of SIGSYS. Result: **238 of 463 blocked**,
  recorded in `tools/blocked-syscalls-x86_64.txt`.

The blocked set is the legacy syscalls: `access`(21), `poll`(7), `pipe`(22),
`dup2`(33), `unlink`(87), `rename`(82), `mkdir`(83), `lstat`(6). Android's
allowlist covers what bionic calls, and bionic only uses the `*at` variants.
musl prefers the legacy calls wherever the ABI still offers them, which on
x86_64 is everywhere.

26 of the blocked calls are ones musl already guards with `#ifdef SYS_...` and
has a modern fallback for. Since the loader is built from source, the fix is to
delete those numbers from `arch/x86_64/bits/syscall.h.in` and let musl compile
the paths it uses on architectures whose ABI never had them.

Which implies aarch64 -- the architecture of an actual phone -- needs no
patching at all: its Linux ABI has only the `*at` variants, so musl already
compiles exactly what Android permits. x86_64 is the awkward case, and it is
awkward only because it is the emulator.

### D8 — DNS by giving the runtime an /etc
Upstream's approach is a Bun `--preload` that calls
`require("dns").setServers([...])`. Tried it, and proved it ran by having the
preload write a marker file -- and the agent still failed with ETIMEOUT, because
Bun's `fetch` resolves through libc `getaddrinfo`, not through the `dns` module.
musl's resolver reads `/etc/resolv.conf`, finds nothing, and falls back to
localhost.

Measured first that the app itself has full network (`tools/netcheck.c`: platform
resolver, TCP 443, and UDP 53 straight to 8.8.8.8 all fine), which separates "no
network" from "no nameserver".

So the shim redirects paths under `/etc` at a directory the app owns, and the app
writes `resolv.conf` into it. That fixes it for any libc consumer rather than for
one runtime's `dns` module, and the preload is gone.

### D9 — where the app lives
The terminal fork's own app module was carrying the agent-launching logic, which
made an upstreamable terminal know what Claude Code is. harness.apk now has its
own Gradle project with `:app`, and pulls the fork's `:terminal-library` in as a
source dependency rather than a published AAR.

The fork's demo app keeps its copy. It is a working demonstration that the
terminal can run a Linux binary, and reverting it churns history for nothing --
but it is duplicated logic, and worth a decision later.

### D10 — the IDE protocol, measured not guessed
Read out of the CLI's bundled JavaScript, which is legible in the binary:

- lockfiles live in `$CLAUDE_CONFIG_DIR/ide`, named `<port>.lock`; the filename
  is the port and nothing inside carries it
- the socket wants subprotocol `mcp` and header
  `X-Claude-Code-Ide-Authorization`
- `callIdeRpc` is `tools/call`, so the four methods the CLI invokes itself have
  to appear in `tools/list` like any other tool
- `openDiff` is judged positionally: `FILE_SAVED` plus contents, `DIFF_REJECTED`,
  or `TAB_CLOSED`, and anything else raises "Not accepted"

Two things were nearly wrong by assumption and were checked instead:
`CLAUDE_CODE_IDE_HOST_OVERRIDE` reads like an IDE name but overrides the host
*IP*, so setting it would have broken the connection; and `--ide` is a real flag
("connect to IDE on startup if exactly one valid IDE is available").

Written up in `docs/ide-protocol.md`.

### D11 — markdown tables
The Compose markdown renderer has no table component in any published version,
and its hook for unknown element types also carries the contents of list items:
overriding it to draw tables silently ate every list item's text. Tables are
lifted out of the source before the renderer sees it, and drawn here. That parser
is the one piece of real logic in the UI, so it has a unit test.

### D12 — panels get their own window
The terminal's GL surface is created with `setZOrderOnTop(true)`, so it is
composited above its own window and nothing drawn in that window can cover it. A
panel is therefore a `Dialog`, which is a window of its own. The map also draws
outside the bounds it is given, so its box is clipped.

## Verified live

- `initialize`, `tools/list`, 8 tools listed, subprotocol negotiated
- `showMarkdown`: headings, bold, italics, ordered and unordered lists,
  blockquote, inline code, a link, and a real table
- `showPlace`: OpenStreetMap tiles with a marker
- `openDiff`: red and green diff, blocked the call, `FILE_SAVED` + contents on
  Apply and `DIFF_REJECTED` on Reject
- `openExternal`: launched Chrome
- the agent itself: boots through the shim, reaches the network, reaches its own
  onboarding

## Not done, and why

**Login.** The copied `~/.credentials.json` is refused even with DNS working, so
the Windows credential store is not portable. Signing in is an interactive OAuth
flow and is the partner's to do. Everything downstream of a signed-in agent --
the agent actually choosing to call these tools -- is therefore unverified.

**arm64.** Google's emulator refuses an arm64 guest on an x86_64 host, so the
architecture that needs no syscall translation is the one that could not be
tested. `stage-claude.sh --abi arm64-v8a` wants a cross compiler passed as `CC`.
