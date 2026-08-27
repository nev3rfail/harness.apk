---
name: firefox-remote-debug
description: Use when something is needed out of a page open in Firefox on this device -- its JSON, its DOM, a value only the loaded page holds -- or when a debugger socket, `adb forward`, `evaluateJSAsync` or a `longString` reply is involved.
---

# Reading a Firefox page on this device

Firefox exposes its remote debugging protocol on an abstract unix socket, and
that socket lets you evaluate JavaScript in a live page and read the result back.
It is the way to get a payload the page already holds without a share link, a
screenshot, or the operator reading anything off the screen.

## What does not work from in here

Both of these are refused, and both look like a bug rather than a policy:

```
open("/proc/net/unix")                              -> Permission denied
connect("\0org.mozilla.firefox/firefox-debugger-socket") -> Permission denied
```

The app's uid may neither list the socket nor connect to it. Do not spend time on
socket-name guessing or on `LD_PRELOAD` tricks: the path is `adb`, which runs
with a uid that is allowed both.

## The route

The operator turns the socket on once, in Firefox: **Settings -> Advanced ->
Remote debugging via USB**. The label says USB; the toggle is what creates the
socket, and USB is not involved.

```sh
pkg install android-tools                 # if adb is not already here
adb connect 127.0.0.1:5555                # accept the debugging dialog once
adb -s 127.0.0.1:5555 shell 'cat /proc/net/unix | grep firefox'
adb -s 127.0.0.1:5555 forward tcp:6080 \
    localabstract:org.mozilla.firefox/firefox-debugger-socket
```

The socket's name carries the package, so it is `org.mozilla.fenix/...` for
Nightly. Two transports appear for one device, so always pass `-s`. After the
forward, `127.0.0.1:6080` is an ordinary TCP socket any language can open.

## The protocol

One packet is `<byte-length>:<json>`, where the length counts UTF-8 bytes of the
JSON that follows. The server sends an unsolicited hello from `root` on connect,
and pushes console and navigation events at any time -- so never treat the next
packet as your reply. Match on `from` and `type`.

```
-> {"to":"root","type":"listTabs"}
<- {"from":"root","tabs":[{"actor":"server0.conn0.tabDescriptor1","url":"...","selected":true}]}

-> {"to":"server0.conn0.tabDescriptor1","type":"getTarget"}
<- {"from":"...","frame":{"consoleActor":"server0.conn0.child2/consoleActor3", ...}}

-> {"to":"...consoleActor3","type":"evaluateJSAsync","text":"JSON.stringify(...)"}
<- {"from":"...consoleActor3","resultID":"1"}
<- {"from":"...consoleActor3","type":"evaluationResult","result":<string or grip>}
```

**A large result does not arrive whole.** It comes as a grip -- `{"type":
"longString", "actor": ..., "length": N, "initial": "<first slice>"}` -- and the
rest is asked for a slice at a time. Derive the first offset from the length of
`initial` rather than assuming its size:

```python
parts, pos = [grip["initial"]], len(grip["initial"])
while pos < grip["length"]:
    end = min(pos + 32768, grip["length"])
    send({"to": grip["actor"], "type": "substring", "start": pos, "end": end})
    parts.append(await_reply()["substring"]); pos = end
send({"to": grip["actor"], "type": "release"})
```

A short result is a plain JSON string instead of a grip. Handle both.

## Client

```python
import socket, json

class RDP:
    def __init__(self, port=6080):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=20)
        self.buf = b""
        self.recv()                      # the root hello

    def recv(self):
        while b":" not in self.buf:
            self.buf += self._read()
        head, rest = self.buf.split(b":", 1)
        n = int(head)
        while len(rest) < n:
            rest += self._read()
        self.buf = rest[n:]
        return json.loads(rest[:n].decode())

    def _read(self):
        d = self.s.recv(65536)
        if not d:
            raise EOFError
        return d

    def send(self, packet):
        b = json.dumps(packet).encode()
        self.s.sendall(str(len(b)).encode() + b":" + b)

    def ask(self, packet, match):
        self.send(packet)
        while True:
            p = self.recv()
            if p.get("error") or match(p):
                return p

    def evaluate(self, console, text):
        return self.ask(
            {"to": console, "type": "evaluateJSAsync", "text": text},
            lambda p: p.get("type") == "evaluationResult",
        ).get("result")
```

## Getting at the payload

Prefer what the page already holds over asking the network again:

- A global, or a framework's cache -- a query client, a store, `__NEXT_DATA__`.
  Walk to it once, stash the result as a string on `window`, then read that
  string. Nothing is re-fetched and nothing new is sent anywhere.
- A credentialed `fetch` from inside the page works, but it is a fresh request
  with the operator's session, and this session's permission rules may refuse
  it. Reach for it only when the page kept nothing.

## Common mistakes

| Mistake | What is true |
|---|---|
| Connecting to the abstract socket from here | Refused for the app's uid. Forward it with `adb` and use TCP |
| Reading `/proc/net/unix` to find the name | Refused too. Read it through `adb shell` |
| Treating the next packet as the reply | Events interleave. Match on `from` and `type` |
| Expecting a big string in one reply | It is a `longString` grip; page it with `substring` |
| Re-fetching the endpoint by reflex | The page usually still holds it, and a fetch spends the operator's session |
