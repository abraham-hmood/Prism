import socket, struct, threading, sys, time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 15037
A_CNXN, A_AUTH, A_OPEN, A_OKAY, A_CLSE, A_WRTE, A_STLS = (
    0x4e584e43, 0x48545541, 0x4e45504f, 0x59414b4f, 0x45534c43, 0x45545257, 0x534c5453)
VERSION = 0x01000000

def checksum(data):
    return sum(data) & 0xffffffff

def readn(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            return None
        data += chunk
    return data

def read_packet(sock):
    hdr = readn(sock, 24)
    if not hdr:
        return None
    cmd, a0, a1, length, csum, magic = struct.unpack("<IIIIII", hdr)
    if magic != (cmd ^ 0xffffffff):
        print("bad magic", hex(cmd), flush=True)
        return None
    data = readn(sock, length) if length else b""
    if data is None:
        return None
    return cmd, a0, a1, data

def write_packet(sock, cmd, a0, a1, data):
    sock.sendall(struct.pack("<IIIIII", cmd, a0, a1, len(data), checksum(data), cmd ^ 0xffffffff) + data)

def handle(conn, addr):
    print("connect", addr, flush=True)
    first = read_packet(conn)
    if not first:
        return
    print("first", hex(first[0]), first[3][:400], flush=True)
    if first[0] == A_CNXN:
        banner = b"device::ro.product.name=prism;ro.product.model=FakePhone;ro.product.device=prism;features=shell_v2,cmd,stat_v2,ls_v2,sendrecv_v2;\x00"
        write_packet(conn, A_CNXN, VERSION, 4096, banner)
    while True:
        packet = read_packet(conn)
        if not packet:
            break
        cmd, a0, a1, data = packet
        print("packet", hex(cmd), "a0", a0, "a1", a1, "len", len(data), "data", data[:300], flush=True)
        if cmd == A_OPEN:
            service = data.rstrip(b"\x00").decode("utf-8", "replace")
            print("SERVICE:", service, flush=True)
            local = 1
            write_packet(conn, A_OKAY, local, a0, b"")
            if service.startswith("shell,v2") or service.startswith("exec:"):
                # exit 0
                payload = bytes([3]) + struct.pack("<I", 1) + bytes([0])
                write_packet(conn, A_WRTE, local, a0, payload)
                write_packet(conn, A_CLSE, local, a0, b"")
            elif service.startswith("sync:"):
                # handle sync commands at packet level
                pass
        elif cmd == A_WRTE:
            # keepalive
            write_packet(conn, A_OKAY, a1, a0, b"")
        elif cmd == A_CLSE:
            pass
    conn.close()
    print("closed", addr, flush=True)

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", PORT))
    server.listen(8)
    print("listening", PORT, flush=True)
    while True:
        conn, addr = server.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()

main()
