#!/bin/bash
# Probes every launchable app on the device, one per :virtualapp process.
#
# One per process on purpose: a probe that wedges the guest's main thread wedges the probe too, and
# the only way out of that is a fresh process. The report file survives, so no result is lost.
set -u

D=${DEVICE:-adb-R5CRC2XEP5Z-MVItoi._adb-tls-connect._tcp}
REPORT=files/virtualization-check.txt
OUT=.artifacts/vcheck-run.log

adbq() { adb -s "$D" "$@"; }

adbq shell am broadcast -a com.prism.launcher.RESET_VIRTUALIZATION_CHECK \
  -n com.prism.launcher/.virtualapp.VirtualAppCheckReceiver >/dev/null 2>&1

if [ $# -gt 0 ]; then
  cp "$1" .artifacts/vcheck-packages.txt
else
  adbq shell cmd package query-activities --brief -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER 2>/dev/null \
    | tr -d '\r' \
    | sed 's/^[[:space:]]*//' \
    | grep -E '^[a-zA-Z0-9_]+\.[a-zA-Z0-9_.]+/' \
    | sed 's#/.*##' \
    | grep -v '^com\.prism\.launcher$' \
    | sort -u > .artifacts/vcheck-packages.txt
fi

total=$(wc -l < .artifacts/vcheck-packages.txt | tr -d ' ')
echo "probing $total apps" | tee -a "$OUT"

has_result() {
  # A finished line for this package: "<pkg>|<verdict>|..."
  adbq shell run-as com.prism.launcher cat "$REPORT" 2>/dev/null | tr -d '\r' | grep -q "^[0-9:]* $1|"
}

kill_guest() {
  local pid
  pid=$(adbq shell pidof com.prism.launcher:virtualapp 2>/dev/null | tr -d '\r')
  if [ -n "$pid" ]; then
    adbq shell run-as com.prism.launcher kill -9 "$pid" >/dev/null 2>&1
  fi
}

# Read into an array FIRST. `adb shell` consumes its caller's stdin, so a `while read` loop over the
# package list stops after the first entry -- which is exactly what happened the first time this ran.
mapfile -t PKGS < .artifacts/vcheck-packages.txt

n=0
for pkg in "${PKGS[@]}"; do
  n=$((n + 1))
  kill_guest
  # FOREGROUND FIRST. A manifest receiver in a background app does not get its broadcast
  # delivered -- the system enqueues it until the app is visible again -- so without this every
  # probe after the launcher stopped being the top app silently did nothing and was recorded as
  # "died" with no stage at all.
  adbq shell am start -n com.prism.launcher/.LauncherActivity >/dev/null 2>&1
  sleep 1
  adbq shell am broadcast -a com.prism.launcher.PROBE_VIRTUALIZATION \
    -n com.prism.launcher/.virtualapp.VirtualAppCheckReceiver --es package "$pkg" >/dev/null 2>&1

  ok=0
  for _ in $(seq 1 20); do
    sleep 2
    if has_result "$pkg"; then ok=1; break; fi
  done

  if [ "$ok" = 1 ]; then
    line=$(adbq shell run-as com.prism.launcher cat "$REPORT" 2>/dev/null | tr -d '\r' | grep "^[0-9:]* $pkg|" | tail -1)
    echo "[$n/$total] $line" | tee -a "$OUT"
  else
    reason=$(adbq shell run-as com.prism.launcher cat "files/virtualized/$pkg/launch-stage.txt" 2>/dev/null | tr -d '\r' | tail -1)
    crash=$(adbq shell run-as com.prism.launcher cat "files/virtualized/$pkg/launch-crash.txt" 2>/dev/null | tr -d '\r' | tr '\n' ' ' | cut -c1-300)
    echo "[$n/$total] $pkg|died|0||stage=${reason:-none} crash=${crash:-none}" | tee -a "$OUT"
  fi

  kill_guest
done

echo "done" | tee -a "$OUT"
