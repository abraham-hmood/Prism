import io
import json
import socket
import struct
import threading
import time
from PIL import Image, ImageDraw

PHONE = "192.168.4.25"
FP = "ebe0e10a3974b1c7b7e0976e84ffca25"
TOKEN = "testtoken1"

width, height = 640, 1280
image = Image.new("RGB", (width, height), (18, 22, 40))
draw = ImageDraw.Draw(image)
for y in range(height):
    draw.line([(0, y), (width, y)], fill=(18 + y // 20, 22 + y // 30, 40 + y // 12))
draw.rectangle([60, 120, width - 60, 420], fill=(124, 108, 255))
draw.text((100, 200), "PRISM MIRROR TEST", fill=(255, 255, 255))
draw.ellipse([220, 700, 420, 900], fill=(50, 209, 88))
buffer = io.BytesIO()
image.save(buffer, "JPEG", quality=80)
jpeg = buffer.getvalue()

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("0.0.0.0", 0))
server.listen(2)
port = server.getsockname()[1]
print("stream port", port, flush=True)


def serve():
    while True:
        client, address = server.accept()
        print("viewer", address, flush=True)
        try:
            client.settimeout(5)
            offered = b""
            while not offered.endswith(b"\n"):
                offered += client.recv(1)
            if offered.strip().decode() != TOKEN:
                client.sendall(b"DENIED\n")
                client.close()
                continue
            client.sendall(b"PRISM-MIRROR/1\n")
            while True:
                client.sendall(struct.pack(">iii", width, height, len(jpeg)) + jpeg)
                time.sleep(0.1)
        except Exception as exc:
            print("viewer ended", exc, flush=True)
            try:
                client.close()
            except Exception:
                pass


threading.Thread(target=serve, daemon=True).start()

control = socket.create_connection((PHONE, 8097), timeout=5)
control.sendall((json.dumps({"t": "hello", "from": "DevPC", "fp": FP, "reverse": False}) + "\n").encode())
reader = control.makefile("rb")
print("reply:", reader.readline().decode().strip(), flush=True)
time.sleep(0.3)
control.sendall((json.dumps({
    "t": "mirror",
    "port": port,
    "token": TOKEN,
    "width": width,
    "height": height,
    "from": "DevPC",
}) + "\n").encode())
print("mirror sent", flush=True)
time.sleep(20)
