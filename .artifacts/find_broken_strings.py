from pathlib import Path

path = Path('desktop/src/main/kotlin/com/prism/desktop/AdbBridge.kt')
lines = path.read_text().splitlines()
for i, line in enumerate(lines, 1):
    n = 0
    j = 0
    while j < len(line):
        if line[j] == '\\':
            j += 2
            continue
        if line[j] == '"':
            n += 1
        j += 1
    if n % 2 == 1:
        print(i, n, repr(line))
