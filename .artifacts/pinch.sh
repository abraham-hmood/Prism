#!/system/bin/sh
# two-finger horizontal pinch outward on /dev/input/event10
D=/dev/input/event10
start1=1500; start2=2590; end1=1100; end2=2990; yy=1570
sev() { sendevent $D "$@"; }
sev 3 47 0; sev 3 57 100; sev 3 53 $start1; sev 3 54 $yy
sev 3 47 1; sev 3 57 101; sev 3 53 $start2; sev 3 54 $yy
sev 1 330 1; sev 0 0 0
i=1
while [ $i -le 10 ]; do
  x1=$((start1 + (end1-start1)*i/10)); x2=$((start2 + (end2-start2)*i/10))
  sev 3 47 0; sev 3 53 $x1; sev 3 54 $yy
  sev 3 47 1; sev 3 53 $x2; sev 3 54 $yy
  sev 0 0 0
  i=$((i+1))
done
sev 3 47 0; sev 3 57 -1
sev 3 47 1; sev 3 57 -1
sev 0 0 0
sev 1 330 0
sev 0 0 0
