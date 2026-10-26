from pathlib import Path

p = Path("desktop/src/main/kotlin/com/prism/desktop/AdbBridge.kt")
text = p.read_text()

lines = text.splitlines()
for i, line in enumerate(lines):
    if '.replace(Regex("[;' in line:
        lines[i] = "                .filter { it != ';' && it != '\\u0000' && it != '\\r' && it != '\\n' }.take(120)"

text = "\n".join(lines) + "\n"
text = text.replace(chr(0), "\\u0000")
# In Kotlin source two backslashes are needed for a regex escape; four are not.
text = text.replace("\\\\s+", "\\s+").replace("\\\\d+", "\\d+")
p.write_text(text)
print("nul", p.read_bytes().count(b"\x00"))
for i, line in enumerate(p.read_text().splitlines(), 1):
    if "u0000" in line or "trimEnd" in line or "-S" in line:
        print(i, repr(line))
