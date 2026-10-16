#!/bin/bash
# usage: shot2.sh name   → ui-hifi/v0.2/name-{light,dark}.png（v0.2 稿）
d=$(cd "$(dirname "$0")/.." && pwd); n=$1
for t in light dark; do h=""; [ $t = dark ] && h="#dark"
 google-chrome --headless=new --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=2 --window-size=412,915 --virtual-time-budget=3000 --screenshot=$d/v0.2/$n-$t.png "file://$d/src/$n.html$h" 2>/dev/null; done
