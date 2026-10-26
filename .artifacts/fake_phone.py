import io
import json
import socket
import struct
import sys
import threading
import time

HOST = "127.0.0.1"
CONTROL = 8097
FP = "72fae6e7aa0a2969ebd33e47b8bb181b"
NAME = "SM-S901U"


def jpeg(w, h, color):
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, "JPEG", quality=70)
    return buf.getvalue()


def recv_exact(sock, n):
    out = b""
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            return None
        out += chunk
    return out


def read_frame(sock):
    header = recv_exact(sock, 12)
    if not header:
        return None
    w, h, ln = struct.unpack(">III", header)
    data = recv_exact(sock, ln)
    if data is None:
        return None
    return w, h, data


def reverse_test():
    print("== reverse: desktop should send its screen ==", flush=True)
    s = socket.create_connection((HOST, CONTROL), timeout=5)
    f = s.makefile("rwb")

    def send(obj):
        print("send", obj, flush=True)
        f.write((json.dumps(obj) + "\n").encode())
        f.flush()

    send({"t": "hello", "from": NAME, "fp": FP, "reverse": True})
    s.settimeout(15)
    mirror_port = None
    token = None
    deadline = time.time() + 20
    while time.time() < deadline:
        line = f.readline()
        if not line:
            print("control closed", flush=True)
            break
        obj = json.loads(line)
        print("recv", obj, flush=True)
        if obj.get("t") == "mirror":
            mirror_port = obj["port"]
            token = obj["token"]
            break
    if not mirror_port:
        print("NO MIRROR MESSAGE FROM DESKTOP", flush=True)
        return False
    print("connecting to desktop stream", mirror_port, flush=True)
    v = socket.create_connection((HOST, mirror_port), timeout=5)
    v.sendall((token + "\n").encode())
    v.settimeout(10)
    greeting = b""
    while not greeting.endswith(b"\n"):
        ch = v.recv(1)
        if not ch:
            break
        greeting += ch
    print("stream greeting", greeting, flush=True)
    frames = 0
    for _ in range(3):
        frame = read_frame(v)
        if not frame:
            break
        frames += 1
        print("frame", frame[0], frame[1], len(frame[2]), "jpeg" if frame[2][:2] == b"\xff\xd8" else "?", flush=True)
    v.close()
    s.close()
    return frames > 0


def mirror_server():
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("0.0.0.0", 0))
    listener.listen(2)
    port = listener.getsockname()[1]
    token = "phonetoken1"

    def serve():
        try:
            conn, addr = listener.accept()
            conn.settimeout(10)
            offered = b""
            while not offered.endswith(b"\n"):
                ch = conn.recv(1)
                if not ch:
                    return
                offered += ch
            print("desktop viewer offered", offered.decode().strip(), flush=True)
            if offered.decode().strip() != token:
                conn.sendall(b"DENIED\n")
                conn.close()
                return
            conn.sendall(b"PRISM-MIRROR/1\n")
            for i in range(900):
                conn.sendall(struct.pack(">III", 640, 360, len(jpeg(640, 360, (255, (i * 5) % 255, 0)))) + jpeg(640, 360, (255, (i * 5) % 255, 0)))
                time.sleep(0.2)
        except Exception as exc:
            print("fake phone stream stopped:", exc, flush=True)

    threading.Thread(target=serve, daemon=True).start()
    return port, token


def display_test():
    print("== normal: desktop should receive and show this screen ==", flush=True)
    s = socket.create_connection((HOST, CONTROL), timeout=5)
    f = s.makefile("rwb")

    def send(obj):
        print("send", obj, flush=True)
        f.write((json.dumps(obj) + "\n").encode())
        f.flush()

    send({"t": "hello", "from": NAME, "fp": FP, "reverse": False})
    line = f.readline()
    print("recv", line, flush=True)
    port, token = mirror_server()
    send({"t": "mirror", "port": port, "token": token, "width": 640, "height": 360, "from": NAME})
    print("offered stream on", port, "- waiting 90s for the desktop to open a viewer", flush=True)
    time.sleep(90)
    s.close()
    return True


mode = sys.argv[1] if len(sys.argv) > 1 else "reverse"
ok = reverse_test() if mode == "reverse" else display_test()
print("RESULT", "ok" if ok else "FAILED", flush=True)
