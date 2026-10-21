#!/usr/bin/env python3
"""Minimal WebSocket listener (stdlib only): ws_listen.py URL SECONDS OUTFILE.

Connects, then appends every text frame the server sends to OUTFILE, one per line, until SECONDS pass.
Only what the E2E needs: the backend pushes small unfragmented, unmasked text frames.
"""
import base64
import os
import socket
import struct
import sys
import time
from urllib.parse import urlparse

url, seconds, out = urlparse(sys.argv[1]), float(sys.argv[2]), sys.argv[3]
sock = socket.create_connection((url.hostname, url.port or 80), timeout=5)
key = base64.b64encode(os.urandom(16)).decode()
path = url.path + ("?" + url.query if url.query else "")
sock.sendall(
    (
        f"GET {path} HTTP/1.1\r\nHost: {url.netloc}\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ).encode()
)
buf = b""
while b"\r\n\r\n" not in buf:
    chunk = sock.recv(4096)
    if not chunk:
        sys.exit("handshake failed: connection closed")
    buf += chunk
head, buf = buf.split(b"\r\n\r\n", 1)
if b" 101 " not in head.split(b"\r\n", 1)[0]:
    sys.exit("handshake failed: " + head.split(b"\r\n", 1)[0].decode())
open(out, "w").close()  # the caller can tell the socket is open once the file exists

deadline = time.time() + seconds
sock.settimeout(1)
while time.time() < deadline:
    try:
        chunk = sock.recv(65536)
        if not chunk:
            break
        buf += chunk
    except socket.timeout:
        pass
    while len(buf) >= 2:
        opcode, length, offset = buf[0] & 0x0F, buf[1] & 0x7F, 2
        if length == 126:
            if len(buf) < 4:
                break
            length, offset = struct.unpack(">H", buf[2:4])[0], 4
        elif length == 127:
            if len(buf) < 10:
                break
            length, offset = struct.unpack(">Q", buf[2:10])[0], 10
        if len(buf) < offset + length:
            break
        payload, buf = buf[offset : offset + length], buf[offset + length :]
        if opcode == 1:
            with open(out, "a") as f:
                f.write(payload.decode() + "\n")
