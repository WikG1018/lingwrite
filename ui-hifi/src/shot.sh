#!/bin/bash
# usage: shot.sh name
n=$1; out=/workspace/ui-hifi
for t in light dark; do
 h=""; [ $t = dark ] && h="#dark"
 google-chrome --headless=new --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=2 --window-size=412,915 --virtual-time-budget=3000 --screenshot=$out/$n-$t.png "file:///workspace/ui-hifi/src/$n.html$h" 2>/dev/null
done
ls -la $out/$n-*.png | awk '{print $5,$9}'
