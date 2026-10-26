import ctypes
import sys
import time
import tkinter

user32 = ctypes.windll.user32
user32.SetCursorPos.argtypes = [ctypes.c_int, ctypes.c_int]
user32.mouse_event.argtypes = [ctypes.c_uint, ctypes.c_uint, ctypes.c_uint, ctypes.c_uint, ctypes.c_void_p]
user32.keybd_event.argtypes = [ctypes.c_ubyte, ctypes.c_ubyte, ctypes.c_uint, ctypes.c_void_p]


def focus(pid):
    hwnd = user32.FindWindowW(None, "Prism")
    if not hwnd:
        raise SystemExit("no Prism window")
    user32.ShowWindow(hwnd, 9)
    user32.SetForegroundWindow(hwnd)
    time.sleep(0.3)
    return hwnd


def click(x, y):
    user32.SetCursorPos(int(x), int(y))
    time.sleep(0.1)
    user32.mouse_event(0x0002, 0, 0, 0, None)
    user32.mouse_event(0x0004, 0, 0, 0, None)


def scroll(x, y, amount):
    user32.SetCursorPos(int(x), int(y))
    time.sleep(0.1)
    user32.mouse_event(0x0800, 0, 0, amount & 0xFFFFFFFF, None)


def key(vk, down=True):
    user32.keybd_event(vk, 0, 0 if down else 2, None)


def paste(text):
    root = tkinter.Tk()
    root.withdraw()
    root.clipboard_clear()
    root.clipboard_append(text)
    root.update()
    time.sleep(0.1)
    key(0x11)
    key(0x56)
    key(0x56, False)
    key(0x11, False)
    root.destroy()


def type_text(text):
    for ch in text:
        scan = user32.VkKeyScanW(ord(ch))
        vk = scan & 0xFF
        shifted = bool((scan >> 8) & 1)
        if shifted:
            key(0x10)
        key(vk)
        key(vk, False)
        if shifted:
            key(0x10, False)
        time.sleep(0.03)


if __name__ == "__main__":
    action = sys.argv[1]
    if action == "focus":
        print(focus(int(sys.argv[2])))
    elif action == "click":
        click(int(sys.argv[2]), int(sys.argv[3]))
    elif action == "scroll":
        scroll(int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]))
    elif action == "paste":
        paste(sys.argv[2])
    elif action == "type":
        type_text(sys.argv[2])
    elif action == "key":
        key(int(sys.argv[2]))
        key(int(sys.argv[2]), False)
