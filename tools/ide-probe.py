#!/usr/bin/env python3
"""Talks to the app's IDE server the way Claude Code does.

Reads a lockfile, opens a WebSocket with the `mcp` subprotocol and the
authorization header the CLI sends, then runs the MCP handshake and whatever
tool calls are asked for. It exists so the server can be exercised without
waiting on a signed-in agent.

    adb forward tcp:PORT tcp:PORT
    ide-probe.py --lock <lockfile> [--port PORT] [tool [json-arguments]]

The lockfile is normally read off the device:

    adb shell run-as <package> cat files/.claude/ide/PORT.lock
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import struct
import sys

AUTH_HEADER = "X-Claude-Code-Ide-Authorization"


class WebSocket:
    """Just enough of RFC 6455 to hold a conversation."""

    def __init__(self, host: str, port: int, token: str, timeout: float = 30.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET / HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"Sec-WebSocket-Protocol: mcp\r\n"
            f"{AUTH_HEADER}: {token}\r\n"
            f"\r\n"
        )
        self.sock.sendall(request.encode())

        response = b""
        while b"\r\n\r\n" not in response:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("closed during the handshake")
            response += chunk
        head, _, rest = response.partition(b"\r\n\r\n")
        status = head.split(b"\r\n")[0].decode()
        if "101" not in status:
            raise ConnectionError(f"handshake refused: {status}\n{head.decode()}")
        self.headers = head.decode()
        self.buffer = rest

    def send(self, text: str) -> None:
        payload = text.encode()
        header = bytearray([0x81])  # FIN, text frame
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 1 << 16:
            header.append(0x80 | 126)
            header += struct.pack(">H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", length)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def _read(self, count: int) -> bytes:
        while len(self.buffer) < count:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("closed")
            self.buffer += chunk
        head, self.buffer = self.buffer[:count], self.buffer[count:]
        return head

    def receive(self) -> str | None:
        while True:
            first, second = self._read(2)
            opcode = first & 0x0F
            length = second & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read(8))[0]
            payload = self._read(length) if length else b""
            if opcode == 0x8:  # close
                return None
            if opcode == 0x9:  # ping; a pong keeps the peer happy
                self.sock.sendall(b"\x8a\x80" + os.urandom(4))
                continue
            if opcode in (0x1, 0x2):
                return payload.decode()

    def close(self) -> None:
        try:
            self.sock.sendall(b"\x88\x80" + os.urandom(4))
        finally:
            self.sock.close()


class Client:
    def __init__(self, ws: WebSocket):
        self.ws = ws
        self.next_id = 1

    def call(self, method: str, params: dict | None = None) -> dict:
        request_id = self.next_id
        self.next_id += 1
        message = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            message["params"] = params
        self.ws.send(json.dumps(message))
        while True:
            raw = self.ws.receive()
            if raw is None:
                raise ConnectionError("closed while waiting for a reply")
            reply = json.loads(raw)
            if reply.get("id") == request_id:
                return reply

    def notify(self, method: str, params: dict | None = None) -> None:
        message = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        self.ws.send(json.dumps(message))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", required=True, help="the lockfile, or its contents")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, help="overrides the port in the lockfile name")
    parser.add_argument("tool", nargs="?", help="a tool to call after the handshake")
    parser.add_argument(
        "arguments",
        nargs="?",
        default="{}",
        help="its arguments, as JSON, or @path to a file holding that JSON "
             "(which avoids a shell rewriting the quotes)",
    )
    args = parser.parse_args()

    if args.arguments.startswith("@"):
        args.arguments = open(args.arguments[1:]).read()

    if os.path.exists(args.lock):
        lock = json.loads(open(args.lock).read())
        port = args.port or int(os.path.basename(args.lock).split(".")[0])
    else:
        lock = json.loads(args.lock)
        if not args.port:
            parser.error("--port is required when the lockfile is passed inline")
        port = args.port

    print(f"connecting to {args.host}:{port} as {lock.get('ideName')}")
    ws = WebSocket(args.host, port, lock["authToken"])
    negotiated = [
        line for line in ws.headers.splitlines()
        if line.lower().startswith("sec-websocket-protocol")
    ]
    print("subprotocol:", negotiated or ["(none offered back)"])

    client = Client(ws)
    hello = client.call("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": "ide-probe", "version": "1"},
    })
    print("initialize:", json.dumps(hello.get("result"), indent=2))
    client.notify("notifications/initialized")

    listing = client.call("tools/list")
    tools = listing.get("result", {}).get("tools", [])
    print(f"tools ({len(tools)}):")
    for tool in tools:
        print(f"  {tool['name']}: {tool.get('description', '')[:70]}")

    if args.tool:
        print(f"\ncalling {args.tool} {args.arguments}")
        outcome = client.call("tools/call", {
            "name": args.tool,
            "arguments": json.loads(args.arguments),
        })
        print(json.dumps(outcome.get("result", outcome), indent=2))

    ws.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
