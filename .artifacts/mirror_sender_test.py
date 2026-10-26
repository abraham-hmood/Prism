import io
import json
import socket
import struct
import threading
import time
from PIL import Image, ImageDraw

PHONE = "192.168.4.25"
BROADCAST = "192.168.7.255"
NAME = "Test Mirror Display"
TOKEN = "test-mirror-token"

WIDTH, HEIGHT = 720, 1280


def image_bytes():
    image = Image.new("RGB", (WIDTH, HEIGHT), "#0a0a12")
    draw = ImageDraw.Draw(image)
    for y in range(HEIGHT):
        shade = int(20 + (y / HEIGHT) * 60)
        draw.line([(0, y), (WIDTH, y)], fill=(shade, shade // 2, 110))
    draw.ellipse((180, 360, 540, 720), fill=(32, 220, 140), outline=(255, 255, 255), width=8)
    draw.ellipse((260, 440, 460, 640), fill=(10, 30, 50))
    draw.rectangle((90, 850, 630, 1030), fill=(255, 255, 255))
    draw.text((140, 900), "PRISM MIRROR TEST", fill=(0, 0, 0))
    draw.text((150, 1080), "This is the phone as DISPLAY", fill=(235, 235, 245))
    draw.text((150, 1130), "If you see this, long-press works.", fill=(160, 230, 200))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=80)
    return buffer.getvalue()


FRAME = image_bytes()


def beacon_loop():
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    udp.bind(("0.0.0.0", 8096))
    payload = json.dumps({"t": "wd", "n": NAME, "p": 8097, "plat": "test"}).encode()
    while True:
        for target in (PHONE, BROADCAST):
            try:
                udp.sendto(payload, (target, 8096))
            except Exception as error:
                print("beacon:", error, flush=True)
        time.sleep(1.5)


threading.Thread(target=beacon_loop, daemon=True).start()
time.sleep(0.4)

media = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
media.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
media.bind(("0.0.0.0", 0))
media.listen(4)
media_port = media.getsockname()[1]
print("media port", media_port, flush=True)


def send_frames(client):
    try:
        reader = client.makefile("rb")
        offered = reader.readline(256).decode("ascii", "replace").strip()
        print("token offer", offered, flush=True)
        if offered != TOKEN:
            client.sendall(b"DENIED\n")
            return
        client.sendall(b"PRISM-MIRROR/1\n")
        header = struct.pack(">iii", WIDTH, HEIGHT, len(FRAME))
        while True:
            client.sendall(header + FRAME)
            time.sleep(0.08)
    except Exception as error:
        print("media ended:", error, flush=True)
    finally:
        try:
            client.close()
        except Exception:
            pass


def read_phone_mirror(host, port, token):
    """The phone is the sender. Connect to its JPEG stream and prove a frame arrives."""
    try:
        sock = socket.create_connection((host, port), 5)
        sock.settimeout(8)
        sock.sendall((token + "\n").encode())
        stream = sock.makefile("rb")
        greeting = stream.readline(64).decode("ascii", "replace").strip()
        print("phone media greeting", greeting, flush=True)
        if greeting != "PRISM-MIRROR/1":
            return
        header = b""
        while len(header) < 12:
            part = stream.read(12 - len(header))
            if not part:
                print("phone media closed", flush=True)
                return
            header += part
        width, height, length = struct.unpack(">iii", header)
        data = b""
        while len(data) < length:
            part = stream.read(length - len(data))
            if not part:
                break
            data += part
        print("PHONE FRAME", width, height, len(data), flush=True)
        if data.startswith(b"\xff\xd8"):
            with open(".artifacts/phone_mirror_frame.jpg", "wb") as handle:
                handle.write(data)
            print("saved phone frame", flush=True)
        sock.close()
    except Exception as error:
        print("phone media failed:", error, flush=True)


def accept_media():
    while True:
        client, address = media.accept()
        print("media viewer", address, flush=True)
        threading.Thread(target=send_frames, args=(client,), daemon=True).start()


threading.Thread(target=accept_media, daemon=True).start()


def handle(client, address):
    print("control from", address, flush=True)
    try:
        client.settimeout(10)
        reader = client.makefile("rb")
        line = reader.readline()
        print("RECV", line.decode("utf-8", "replace").strip(), flush=True)
        hello = json.loads(line)
        print("hello", hello, flush=True)
        client.sendall(b'{"t":"accepted"}\n')
        client.settimeout(None)
        reverse = bool(hello.get("reverse"))
        if reverse:
            # The phone is the display; this fake desktop sends its screen.
            time.sleep(0.3)
            message = {
                "t": "mirror",
                "port": media_port,
                "token": TOKEN,
                "width": WIDTH,
                "height": HEIGHT,
                "from": NAME,
            }
            client.sendall((json.dumps(message) + "\n").encode())
            print("sent mirror", message, flush=True)
        else:
            # The phone is the sender. Wait for its announcement, then read a real frame.
            print("waiting for phone mirror", flush=True)
        while True:
            line = reader.readline()
            if not line:
                break
            text = line.decode("utf-8", "replace").strip()
            print("RECV", text, flush=True)
            try:
                message = json.loads(text)
            except Exception:
                continue
            if message.get("t") == "mirror" and not reverse:
                host = address[0]
                threading.Thread(
                    target=read_phone_mirror,
                    args=(host, int(message["port"]), message["token"]),
                    daemon=True,
                ).start()
    except Exception as error:
        print("control ended:", error, flush=True)
    finally:
        try:
            client.close()
        except Exception:
            pass


control = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
control.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
control.bind(("0.0.0.0", 8097))
control.listen(4)
print("fake display ready", flush=True)

while True:
    client, address = control.accept()
    threading.Thread(target=handle, args=(client, address), daemon=True).start()
