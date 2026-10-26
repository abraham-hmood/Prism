import json
import socket
import time

PHONE = "192.168.4.25"
FP = "ebe0e10a3974b1c7b7e0976e84ffca25"

control = socket.create_connection((PHONE, 8097), timeout=5)
control.sendall((json.dumps({
    "t": "hello",
    "from": "DevPC",
    "fp": FP,
    "reverse": False,
}) + "\n").encode())
control.settimeout(6)
reader = control.makefile("rb")
line = reader.readline()
print("reply:", line.decode().strip(), flush=True)
time.sleep(0.3)
control.sendall((json.dumps({
    "t": "page",
    "slot": "desktop_grid",
    "index": 0,
    "from": "DevPC",
}) + "\n").encode())
print("page sent", flush=True)
time.sleep(18)
control.close()
