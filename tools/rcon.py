"""Minimal RCON client: python3 tools/rcon.py "command" [more commands...]"""
import socket
import struct
import sys

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "heaven"


def packet(req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def read(sock):
    length = struct.unpack("<i", sock.recv(4))[0]
    data = b""
    while len(data) < length:
        data += sock.recv(length - len(data))
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, data[8:-2].decode("utf-8", "replace")


with socket.create_connection((HOST, PORT), timeout=10) as s:
    s.sendall(packet(1, 3, PASSWORD))
    if read(s)[0] == -1:
        sys.exit("rcon auth failed")
    for i, cmd in enumerate(sys.argv[1:], start=2):
        s.sendall(packet(i, 2, cmd))
        print(f"> {cmd}\n{read(s)[1]}")
