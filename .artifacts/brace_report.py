from pathlib import Path
p = Path('desktop/src/main/kotlin/com/prism/desktop/AdbBridge.kt')
depth = 0
for i, line in enumerate(p.read_text().splitlines(), 1):
    # naive: strip quoted strings and line comments
    out = []
    in_str = False
    in_char = False
    j = 0
    while j < len(line):
        c = line[j]
        if in_str:
            if c == '\\':
                j += 2
                continue
            if c == '"':
                in_str = False
            j += 1
            continue
        if in_char:
            if c == '\\':
                j += 2
                continue
            if c == "'":
                in_char = False
            j += 1
            continue
        if c == '"':
            in_str = True
        elif c == "'":
            in_char = True
        elif line.startswith('//', j):
            break
        else:
            out.append(c)
        j += 1
    s = ''.join(out)
    before = depth
    depth += s.count('{') - s.count('}')
    if i in range(400, 430) or i in range(570, 720) or i > 840:
        print(i, before, '->', depth, line[:100])
print('final depth', depth)
