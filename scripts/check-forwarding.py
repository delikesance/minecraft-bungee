#!/usr/bin/env python3
"""Negative forwarding probes against the disposable Paper 1.21.4 stack.

Requires local Docker-host access. Does not log in as a real player or change data.
"""
import argparse
import base64
from pathlib import Path
import json
import socket
import struct
import subprocess
import uuid


def varint(value):
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out) + bytes([value])


def read_varint(data, position=0):
    value = shift = 0
    for _ in range(5):
        byte = data[position]
        position += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, position
        shift += 7
    raise ValueError("invalid VarInt")


def string(value):
    encoded = value.encode()
    return varint(len(encoded)) + encoded


def packet(sock):
    header = bytearray()
    for _ in range(5):
        byte = sock.recv(1)
        if not byte:
            raise EOFError("connection closed before packet")
        header += byte
        if byte[0] < 128:
            break
    size, _ = read_varint(header)
    if size > 1024 * 1024:
        raise ValueError("oversized packet")
    data = bytearray()
    while len(data) < size:
        part = sock.recv(size - len(data))
        if not part:
            raise EOFError("truncated packet")
        data += part
    return bytes(data)


def check_icon(protocol):
    with socket.create_connection(("127.0.0.1", 25565), timeout=5) as sock:
        handshake = varint(0) + varint(protocol) + string("localhost") + struct.pack(">H",25565) + varint(1)
        sock.sendall(varint(len(handshake)) + handshake + b"\x01\x00")
        response = packet(sock)
        kind,cursor=read_varint(response)
        assert kind==0
        length,cursor=read_varint(response,cursor)
        status=json.loads(response[cursor:cursor+length])
        icon=base64.b64decode(status["favicon"].split(",",1)[1])
        expected=Path(__file__).resolve().parent.parent/"infra/server-icon.png"
        # Velocity re-encodes PNGs through ImageIO; compressed bytes need not match.
        assert expected.is_file()
        assert struct.unpack(">II",icon[16:24])==(64,64)
        print("PASS: server-list ping returns a 64x64 PNG icon")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="vampyuyu-local-creative-1")
    parser.add_argument("--protocol", type=int, default=769, help="769 = Minecraft 1.21.4")
    args = parser.parse_args()
    check_icon(args.protocol)
    info = json.loads(subprocess.check_output(["docker", "inspect", args.container]))[0]
    networks = info["NetworkSettings"]["Networks"]
    assert len(networks) == 1, "expected exactly one backend network"
    address = next(iter(networks.values()))["IPAddress"]
    for forged in (False, True):
        with socket.create_connection((address, 25565), timeout=5) as sock:
            def send(data):
                sock.sendall(varint(len(data)) + data)
            send(varint(0) + varint(args.protocol) + string("creative") + struct.pack(">H", 25565) + varint(2))
            send(varint(0) + string("SmokeProbe") + uuid.uuid4().bytes)
            challenge = packet(sock)
            kind, cursor = read_varint(challenge)
            assert kind == 4, "expected Velocity forwarding query"
            transaction, cursor = read_varint(challenge, cursor)
            length, cursor = read_varint(challenge, cursor)
            assert challenge[cursor:cursor + length] == b"velocity:player_info"
            # Correct transaction ID; only the forwarding authentication is invalid.
            response = b"\x01" + bytes(32) + varint(1) if forged else b"\x00"
            send(varint(2) + varint(transaction) + response)
            rejected = packet(sock)
            kind, _ = read_varint(rejected)
            assert kind == 0, "backend accepted an untrusted player"
            if forged:
                assert b"Unable to verify player details" in rejected, rejected
            else:
                assert b"Velocity" in rejected, rejected
            print("PASS: invalid forwarding signature rejected" if forged else "PASS: direct connection without forwarding rejected")


if __name__ == "__main__":
    main()
