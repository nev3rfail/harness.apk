---
name: self-adb
description: Use to reach this device's own Android surface from inside the app -- read or pull files out of another app on the phone (another harness channel, its Claude sessions, its data), or run shell commands such as pm, am, dumpsys and getprop -- by pointing adb at this phone's own adbd over loopback. Covers getting in when nothing is listening (mDNS discovery over Wi-Fi, then pinning `tcpip 5555` so it keeps working on cellular), pairing, `run-as`, and tarring files out of another package. Ships the mDNS-capable `adb-mdns` binary. For a page open in Firefox use firefox-remote-debug; this is for the device and other apps, not a web page.
---

# adb'ing this device from inside the app

adbd (the on-device debug daemon) can listen on a TCP port, and the app's own
uid is allowed to open a loopback socket to it. So `adb connect 127.0.0.1:<port>`
from in here lands a shell on the very phone the app runs on. That shell runs as
the `shell` uid -- more than the app's uid, less than root -- and its useful
power is `run-as`, which reads the private storage of any **debuggable** package.
That is the route to another channel's files: `apk.harness` reaching into
`dev.harness`, or either one's Claude sessions.

## Two routes, and why you want the loopback one

There are two ways adbd can be reachable, and they are not interchangeable:

| | **Loopback `tcpip 5555`** | **Wireless debugging (TLS)** |
|---|---|---|
| Listens on | every interface, **including `127.0.0.1`** | the **Wi-Fi** address only |
| On cellular, Wi-Fi off | **works** | dead -- nothing to connect to |
| Port | fixed `5555` | random, changes every toggle and reboot |
| Trust | the classic key in `adb_keys` | a separate paired-TLS keystore |
| Survives reboot | no (the property resets) | yes (the pairing does) |

The loopback route is the one you actually want: it is the only one that answers
when the phone is on mobile data, which is most of the time. Wireless debugging
is how you *reach in to turn it on* without anyone touching the screen.

So the route is **discover over Wi-Fi once, then pin the loopback port**:

```sh
adb-mdns start-server
S=$(adb-mdns devices | tail -n +2 | awk 'NF{print $1}')   # mDNS instance name
adb-mdns -s "$S" tcpip 5555        # adbd restarts; the mdns transport drops
adb-mdns connect 127.0.0.1:5555    # now reachable with no Wi-Fi at all
```

`tcpip 5555` sets `service.adb.tcp.port=5555`, and from then on
`127.0.0.1:5555` works on cell, with no discovery and nobody looking at
anything. Re-do the four lines after a reboot, which clears the property.

If Wi-Fi is already gone and 5555 was never pinned, there is no way in from
here -- that is why you pin it while you can.

## Everyday use

```sh
adb-mdns connect 127.0.0.1:5555     # usually already connected
D="adb-mdns -s 127.0.0.1:5555"
$D shell id
```

`adb-mdns` is a locally built adb **with mDNS discovery on**, which stock Termux
adb lacks; it ships next to this skill in `bin/adb-mdns`. Install it and stop
worrying about ports:

```sh
cp $HOME/.claude/skills/self-adb/bin/adb-mdns $PREFIX/bin/ && chmod +x $PREFIX/bin/adb-mdns
```

The copy out of the assets carries content and not permissions, which is what the
`chmod` is for.

The shipped binary is **aarch64**. On any other architecture it will not exec, and
the answer is to build one: see [build_instructions.md](build_instructions.md),
which is also how to rebuild this one from source.

## When there is no device at all

Discovery needs **Wireless debugging on**, this host **paired**, and the phone
**on Wi-Fi**. Pairing is one-time and survives reboots, but the 6-digit code is
only ever on the phone's screen -- nothing derives it, and that is Google's
design, not a gap to route around.

```sh
adb-mdns mdns services    # empty = wireless debugging off, not on Wi-Fi, or on another network
```

If services appear but connecting fails with `SSLV3_ALERT_CERTIFICATE_UNKNOWN`,
this host is not paired. Ask the operator to open **Settings -> Developer
options -> Wireless debugging -> Pair device with pairing code** and, *while
that dialog stays on screen*, read you its port and code. Pair with **stock
adb**, because `adb-mdns pair` segfaults:

```sh
adb pair 192.168.31.6:<pair-port> <6-digit-code>    # stock adb, not adb-mdns
```

Both port and code die when the dialog closes, so pair immediately; while it is
open a `_adb-tls-pairing._tcp` service shows in `mdns services`, which is a
cheap way to tell. Then go straight to pinning 5555 -- do not leave the device
reachable only over Wi-Fi.

A key trusted through the classic route is **not** trusted for
`_adb-tls-connect` and vice versa: two keystores, two answers.

## Getting stock adb too

`adb-mdns` does everything except pairing, and pairing needs stock adb:

```sh
apt install -y android-tools        # provides adb; ~2x normal install time here
```

## The classifier will block adb and apt

In auto mode both `apt` and `adb` are denied by the classifier until there is a
permission rule. A verbal go-ahead does not reach the classifier -- add a rule
(update-config skill, or the operator via settings). Scope it:

```json
{ "permissions": { "allow": ["Bash(adb:*)"] } }
```

`apt` needs the same. Once the rule is in `.claude/settings.local.json` the
commands go through.

## Two details that bite

**`$HOME`.** The key and the paired-device state live in `$HOME/.android`, so a
session with a different `$HOME` looks like an unpaired host. Export it before
anything else:

```sh
export HOME="$PREFIX/../home"
```

**`-s`.** Several transports can be connected at once -- `127.0.0.1:5555`, an
mDNS instance, a stale `emulator-5554` -- so always name the one you mean:

```sh
D="adb-mdns -s 127.0.0.1:5555"
```

The classic route raises the on-screen "Allow USB debugging?" fingerprint dialog
the first time a new key is seen, and the device sits in `unauthorized` until it
is tapped. The paired TLS route never asks: it connects or it fails the
handshake. Discovery is also not instant -- poll `devices` for a few seconds
after `start-server` before concluding nothing is there.

## Reaching another app's files

`run-as <pkg>` only works if that package is **debuggable** (the dev/stg/apk
harness builds are). Its working directory is the app's data dir, so private
files are under `files/`:

```sh
$D shell run-as dev.harness ls -la files
$D shell run-as dev.harness ls -la files/.claude/projects   # its Claude sessions
```

A Claude session lives under `files/.claude/projects/<sanitised-cwd>/`, where the
cwd is path-mangled: `/data/data/dev.harness/files/travel` becomes
`-data-data-dev-harness-files-travel`.

## Pulling files out

`adb pull` cannot see through `run-as`, and the `shell` uid cannot read the
files directly. Stream a tar built **inside** run-as instead. `tar` is not on
run-as's PATH; call toybox by absolute path. Use `exec-out` (binary-clean, no tty
mangling):

```sh
# a whole directory
$D exec-out run-as dev.harness /system/bin/tar -c -C files travel > travel.tar

# a session dir whose name starts with '-' -- prefix ./ or tar reads it as a flag
$D exec-out run-as dev.harness /system/bin/tar -c -C files/.claude/projects \
   ./-data-data-dev-harness-files-travel > session.tar
```

Then extract locally. `/tmp` is not writable -- stage in the scratchpad or
`$TMPDIR`. When transplanting a session to run under a new path, rename the
project dir to the destination's mangled cwd and rewrite the old cwd string
inside the `.jsonl` transcripts (`sed -i 's#/old/path#/new/path#g'`) so it
resumes cleanly.

## Common mistakes

| Mistake | What is true |
|---|---|
| Treating wireless debugging as the destination | It binds to **Wi-Fi only**, so it is dead on cell. It is the bootstrap; `tcpip 5555` is the route you keep |
| Leaving 5555 unpinned while Wi-Fi is up | Once Wi-Fi is gone there is no way in. Pin it the moment you have a connection |
| Thinking the loopback route is deprecated | It is the only one that answers on mobile data |
| Asking the operator for a port | `adb-mdns` discovers it. Only pairing ever needs them, and only once |
| Using stock `adb` for everyday work | It has no mDNS, so it cannot find the random port. Use `adb-mdns`; stock `adb` is only for `pair` |
| `adb-mdns pair` | Segfaults the server in this build. Pair with stock `adb`, then switch back |
| Reusing a pairing port or code | Both die when the dialog closes. Pair while it is on screen |
| Expecting one keystore to cover both routes | Classic `adb_keys` and the paired-TLS store are separate; the TLS path answers `SSLV3_ALERT_CERTIFICATE_UNKNOWN` until paired |
| Expecting 5555 to survive a reboot | The property resets. Re-run the discover-then-`tcpip` bootstrap |
| Concluding "nothing is advertised" too fast | Discovery takes a few seconds after `start-server`; poll before giving up |
| Retrying adb verbatim after a classifier denial | Add a `Bash(adb:*)` rule; the block is not about the command being wrong |
| Omitting `-s` | Several transports can exist at once; commands hit the wrong one |
| `adb pull` from another app's data | Blocked; the `shell` uid can't read it. `run-as` + tar via `exec-out` |
| `tar` / bare dir name starting with `-` | tar not on run-as PATH (`/system/bin/tar`); prefix the name with `./` |
| Staging in `/tmp` | Not writable. Use the scratchpad or `$TMPDIR` |
| `run-as` on a non-debuggable app | Refused. Only debuggable packages (the harness builds) open up |
