import json
import socket
import struct
import sys
import threading
import time

PHONE = "192.168.4.25"
BCAST = "192.168.7.255"
UDP = 8096
CONTROL = 8097

name = sys.argv[1] if len(sys.argv) > 1 else "Test Desktop"
reverse = "--send" in sys.argv   # behave as the sender (reverse link) if the phone asks

stop = False


def beacon(sock):
    payload = json.dumps({
        "t": "wd", "n": name, "p": CONTROL, "plat": "test", "fp": "",
    }).encode()
    while not stop:
        for addr in (BCAST, PHONE):
            try:
                sock.sendto(payload, (addr, UDP))
            except Exception as exc:
                print("beacon send failed", exc, flush=True)
        time.sleep(1.5)


def udp_loop():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind(("", UDP))
    except Exception as exc:
        print("udp bind failed", exc, flush=True)
    threading.Thread(target=beacon, args=(sock,), daemon=True).start()
    while not stop:
        try:
            data, addr = sock.recvfrom(4096)
            obj = json.loads(data.decode())
            if obj.get("t") == "wd" and addr[0] != PHONE:
                print("peer beacon from", addr, obj.get("n"), flush=True)
        except Exception:
            pass


def mirror_server():
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("", 0))
    listener.listen(2)
    port = listener.getsockname()[1]
    token = "testtoken1"

    def serve():
        try:
            conn, addr = listener.accept()
            conn.settimeout(10)
            offered = b""
            while not offered.endswith(b"\n"):
                chunk = conn.recv(1)
                if not chunk:
                    return
                offered += chunk
            print("mirror client offered token:", offered.decode().strip(), flush=True)
            if offered.decode().strip() != token:
                conn.sendall(b"DENIED\n")
                conn.close()
                return
            conn.sendall(b"PRISM-MIRROR/1\n")
            # A few synthetic frames, so the phone has something to draw.
            for i in range(600):
                img = solid_jpeg(320, 640, (i * 7) % 255, 40, 90)
                conn.sendall(struct.pack(">III", 320, 640, len(img)) + img)
                time.sleep(0.2)
        except Exception as exc:
            print("mirror server stopped:", exc, flush=True)

    threading.Thread(target=serve, daemon=True).start()
    return port, token


def solid_jpeg(w, h, r, g, b):
    from PIL import Image
    import io
    buf = io.BytesIO()
    Image.new("RGB", (w, h), (r, g, b)).save(buf, "JPEG", quality=60)
    return buf.getvalue()


def control_loop():
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("", CONTROL))
    listener.listen(4)
    print("control listening on", CONTROL, flush=True)
    while not stop:
        conn, addr = listener.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


def handle(conn, addr):
    print("connection from", addr, flush=True)
    f = conn.makefile("rwb")

    def send(obj):
        line = (json.dumps(obj) + "\n").encode()
        print("send:", obj, flush=True)
        f.write(line)
        f.flush()

    hello = f.readline()
    print("hello:", hello.decode(errors="replace").strip(), flush=True)
    try:
        opening = json.loads(hello)
        wants_us_to_send = bool(opening.get("reverse"))
        send({"t": "accepted"})
        if wants_us_to_send:
            port, token = mirror_server()
            send({"t": "mirror", "port": port, "token": token, "width": 320, "height": 640, "from": name})
        while True:
            line = f.readline()
            if not line:
                break
            print("recv:", line.decode(errors="replace").strip(), flush=True)
            try:
                obj = json.loads(line)
            except Exception:
                continue
            if obj.get("t") == "mirror" and not wants_us_to_send:
                threading.Thread(target=view, args=(obj,), daemon=True).start()
    except Exception as exc:
        print("handler failed:", exc, flush=True)
    finally:
        print("connection closed", flush=True)


def view(obj):
    host = PHONE
    port = int(obj.get("port"))
    token = obj.get("token") or ""
    print("connecting to mirror", host, port, flush=True)
    try:
        sock = socket.create_connection((host, port), timeout=5)
        sock.sendall((token + "\n").encode())
        hello = b""
        while not hello.endswith(b"\n"):
            chunk = sock.recv(1)
            if not chunk:
                print("mirror closed before greeting", flush=True)
                return
            hello += chunk
        print("mirror greeting:", hello.decode().strip(), flush=True)
        sock.settimeout(8)
        count = 0
        while count < 5:
            header = recv_exact(sock, 12)
            if not header:
                break
            w, h, ln = struct.unpack(">III", header)
            data = recv_exact(sock, ln)
            count += 1
            print("frame", count, w, h, ln, "jpeg" if data[:2] == b"\xff\xd8" else "?", flush=True)
    except Exception as exc:
        print("mirror view failed:", exc, flush=True)


def recv_exact(sock, n):
    out = b""
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            return None
        out += chunk
    return out


threading.Thread(target=udp_loop, daemon=True).start()
control_loop()
