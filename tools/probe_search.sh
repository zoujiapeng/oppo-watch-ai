#!/data/data/com.termux/files/usr/bin/bash
# 搜索胶囊取证巡检：反复开/关搜索层，每次开完拍一张取证状态，最后把
# 「blob（弹簧） vs barReal（输入行窗口真实位置）」逐条列出来。
# 用法： bash tools/probe_search.sh [轮数]
set -u
N="${1:-6}"
D=/sdcard/Android/data/com.glslplayground.app/files
OUT="$HOME/OPPO-watch-ai/shots/probe"
mkdir -p "$OUT"
: > "$OUT/search-probe.txt"
for i in $(seq 1 "$N"); do
  su -c "touch $D/search-close" 2>/dev/null
  sleep 1.0
  su -c "touch $D/search-open" 2>/dev/null
  sleep 1.8
  su -c "touch $D/witness" 2>/dev/null
  sleep 0.5
  f=$(su -c "ls -t $D/glass_*.txt 2>/dev/null | head -1")
  echo "===== round $i =====" >> "$OUT/search-probe.txt"
  su -c "cat $f" >> "$OUT/search-probe.txt" 2>/dev/null
  sleep 3.2
done
grep -E "=====|blob|barReal|barReq|search=|springs|glassWin|flags" "$OUT/search-probe.txt" | tail -60
