from pathlib import Path
p = Path('desktop/src/main/kotlin/com/prism/desktop/AdbBridge.kt')
s = p.read_text()
print('literal backslash-n count:', s.count('\\n'))
print('newlines:', s.count('\n'))
for i, l in enumerate(s.splitlines(), 1):
    if l.rstrip().endswith('\\'):
        print('TRAILING', i, repr(l[-70:]))
    if 'u0000' in l:
        print('NUL', i, repr(l))
