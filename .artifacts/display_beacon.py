import json
import socket
import threading
import time

NAME = "Test TV"
BROADCAST = "192.168.7.255"
PHONE = "192.168.4.25"
BEACON = json.dumps({"t": "wd", "n": NAME, "p": 8097, "plat": "test"}).encode()

udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
udp.bind(("0.0.0.0", 8096))


def beacon():
    while True:
        try:
            udp.sendto(BEACON, (BROADCAST, 8096))
            udp.sendto(BEACON, (PHONE, 8096))
        except Exception as exc:
            print("beacon failed", exc, flush=True)
        time.sleep(2)


def print_lines(client):
    try:
        reader = client.makefile()
        for line in reader:
            print("RECV", line.strip(), flush=True)
    except Exception as exc:
        print("client ended", exc, flush=True)


threading.Thread(target=beacon, daemon=True).start()

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("0.0.0.0", 8097))
server.listen(5)
print("fake display listening", flush=True)

while True:
    client, address = server.accept()
    print("connected from", address, flush=True)
    threading.Thread(target=print_lines, args=(client,), daemon=True).start()
