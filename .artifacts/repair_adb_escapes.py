from pathlib import Path
p = Path('desktop/src/main/kotlin/com/prism/desktop/AdbBridge.kt')
s = p.read_text()
s = s.replace('\\\r\n', '\\n').replace('\\\n', '\\n')
s = s.replace('\\\\u0000', '\\u0000')
p.write_text(s)
print('remaining broken line endings:', sum(1 for l in s.splitlines() if l.rstrip().endswith('\\')))
print('double-escaped NUL:', s.count('\\\\u0000'))
