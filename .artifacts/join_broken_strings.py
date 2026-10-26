from pathlib import Path

path = Path('desktop/src/main/kotlin/com/prism/desktop/AdbBridge.kt')
lines = path.read_text().splitlines()
out = []
i = 0
while i < len(lines):
    line = lines[i]
    n = 0
    j = 0
    while j < len(line):
        if line[j] == '\\':
            j += 2
            continue
        if line[j] == '"':
            n += 1
        j += 1
    if n % 2 == 1 and i + 1 < len(lines):
        nxt = lines[i + 1]
        out.append(line + '\\n' + nxt.lstrip())
        i += 2
    else:
        out.append(line)
        i += 1
path.write_text('\n'.join(out) + '\n')
print('wrote', len(out), 'lines')
