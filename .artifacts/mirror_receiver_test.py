import json
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
CONTROL = 8097
FP = "72fae6e7aa0a2969ebd33e47b8bb181b"
NAME = "SM-S901U"


def lines(sock):
    f = sock.makefile("rb")
    while True:
        line = f.readline()
        if not line:
            return
        yield json.loads(line.decode())


control = socket.create_connection((HOST, CONTROL), timeout=5)
control.sendall((json.dumps({"t": "hello", "from": NAME, "fp": FP, "reverse": True}) + "\n").encode())
print("sent hello", flush=True)

mirror = None
control.settimeout(12)
control_file = control.makefile("rb")
while True:
    try:
        line = control_file.readline()
    except socket.timeout:
        print("timed out waiting for the mirror offer", flush=True)
        break
    if not line:
        print("control closed", flush=True)
        break
    message = json.loads(line.decode())
    print("control:", message, flush=True)
    if message.get("t") == "mirror":
        mirror = message
        break

if mirror is None:
    print("NO MIRROR OFFERED", flush=True)
    sys.exit(2)

stream = socket.create_connection((HOST, mirror["port"]), timeout=5)
stream.sendall((mirror["token"] + "\n").encode())
greeting = stream.makefile("rb").readline()
print("greeting:", greeting, flush=True)

frames = []
stream.settimeout(5)
buffer = b""
while len(frames) < 5:
    chunk = stream.recv(1 << 20)
    if not chunk:
        break
    buffer += chunk
    while len(buffer) >= 12:
        width, height, length = struct.unpack(">iii", buffer[:12])
        if len(buffer) < 12 + length:
            break
        jpeg = buffer[12:12 + length]
        frames.append((width, height, jpeg))
        buffer = buffer[12 + length:]
    print("frames so far", len(frames), flush=True)

for index, (width, height, jpeg) in enumerate(frames):
    print("frame", index, width, height, len(jpeg), "jpeg" if jpeg[:2] == b"\xff\xd8" else "NOT-JPEG", flush=True)
    open(f".artifacts/desktop_frame_{index}.jpg", "wb").write(jpeg)

print("OK", len(frames), flush=True)
