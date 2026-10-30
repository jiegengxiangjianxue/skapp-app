#!/usr/bin/env bash
# skapp 真机六项验证：详情页技术指标 / 多日趋势 / 推股自动推荐 / 首屏耗时 / 断网空态 / 限流空态
#
# 用法：
#   ./verify_device_six.sh skapp-2.2.8-vc33-arm64-fixed.apk
#   DEEP_WAIT=180 ./verify_device_six.sh <apk>        # 首轮全市场深算慢时调大等待
#
# 依赖：adb 在 PATH、主机有 python3（只用来解析 uiautomator 的 XML）
# 产出：verify-out-<时间戳>/{report.md, *.png, *.xml, logcat.txt}
#
# 判据（写死在脚本里，避免事后各说一套）：
#   1 详情页技术指标  dump 中出现 MACD/KDJ/RSI/BOLL 至少 3 个关键词
#   2 多日趋势        「多日趋势：」条目数 == 名单只数，且名单只数 > 5
#   3 推股自动推荐    名单标题「N 只」的 N >= 6（即不是只算前 5 只）
#   4 首屏耗时        am start -W 的 TotalTime 有数值，同时记录 logcat Displayed
#   5 断网空态        注入 OFFLINE 后点重算，dump 出现失败/空态文案且不是崩溃
#   6 限流空态        注入 RATE_LIMIT 后点重算，dump 出现限流/失败文案
set -uo pipefail

ADB=${ADB:-adb}
PKG=com.skapp.app
ACT=$PKG/.MainActivity
APK=${1:-}
DEEP_WAIT=${DEEP_WAIT:-150}   # 首次全市场深算的等待秒数
OUT=${OUT:-verify-out-$(date +%Y%m%d-%H%M%S)}
PASS=0; FAIL=0; declare -a LINES

say()  { printf '%s\n' "$*"; }
pass() { PASS=$((PASS+1)); LINES+=("| $1 | PASS | $2 |"); say "  [PASS] $1 — $2"; }
fail() { FAIL=$((FAIL+1)); LINES+=("| $1 | FAIL | $2 |"); say "  [FAIL] $1 — $2"; }

command -v "${ADB%% *}" >/dev/null || { say "找不到 adb，请把 platform-tools 加进 PATH"; exit 2; }
command -v python3 >/dev/null || { say "需要 python3 解析 uiautomator XML"; exit 2; }
mkdir -p "$OUT"

# 在 dump 出来的 XML 里按文本找可点节点中心坐标，输出 "x y"，找不到输出空
cat > "$OUT/tap.py" <<'PY'
import sys, re, xml.etree.ElementTree as ET
xml_path, needle, mode = sys.argv[1], sys.argv[2], (sys.argv[3] if len(sys.argv) > 3 else 'contains')
try:
    root = ET.parse(xml_path).getroot()
except Exception:
    sys.exit(1)
def hit(t):
    if t is None: return False
    return t.strip() == needle if mode == 'exact' else needle in t
for n in root.iter('node'):
    if not hit(n.get('text')) and not hit(n.get('content-desc')):
        continue
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds') or '')
    if not m: continue
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1: continue
    print((x1 + x2)//2, (y1 + y2)//2)
    sys.exit(0)
sys.exit(1)
PY

dump() {  # dump 当前界面，参数为文件名前缀
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1
}
shot() { "$ADB" exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
tap_text() {  # tap_text <文件前缀> <文本> [exact|contains]
    dump "$1-tmp"
    local xy; xy=$(python3 "$OUT/tap.py" "$OUT/$1-tmp.xml" "$2" "${3:-contains}" 2>/dev/null) || return 1
    [ -n "$xy" ] || return 1
    # shellcheck disable=SC2086
    "$ADB" shell input tap $xy
    sleep 1.5
    return 0
}
count_text() {  # count_text <xml> <子串>
    python3 - "$1" "$2" <<'PY'
import sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: print(0); raise SystemExit
needle = sys.argv[2]
n = 0
for e in root.iter('node'):
    for k in ('text', 'content-desc'):
        v = e.get(k) or ''
        if needle in v:
            n += v.count(needle)
            break
print(n)
PY
}
has_any() {  # has_any <xml> <词1> <词2> ...  输出命中的词数
    python3 - "$@" <<'PY'
import sys, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: print(0); raise SystemExit
blob = ' '.join((e.get('text') or '') + ' ' + (e.get('content-desc') or '') for e in root.iter('node'))
print(sum(1 for w in sys.argv[2:] if w in blob))
PY
}

say "=== 0. 设备与安装 ==="
"$ADB" get-state >/dev/null 2>&1 || { say "没有可用设备：adb devices 为空"; exit 2; }
MODEL=$("$ADB" shell getprop ro.product.model | tr -d '\r')
SDK=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
say "  设备 $MODEL / API $SDK"

if [ -n "$APK" ]; then
    [ -f "$APK" ] || { say "APK 不存在：$APK"; exit 2; }
    "$ADB" install -r -d "$APK" | tail -1
fi
VCODE=$("$ADB" shell dumpsys package $PKG | grep -m1 versionCode | tr -d '\r ')
say "  已装版本：$VCODE"

"$ADB" shell am force-stop $PKG
"$ADB" logcat -c

say "=== 4. 首屏耗时 ==="
START=$("$ADB" shell am start -W -n $ACT 2>&1 | tr -d '\r')
echo "$START" > "$OUT/am-start.txt"
TOTAL=$(printf '%s\n' "$START" | awk -F': *' '/TotalTime/{print $2; exit}')
THIS_T=$(printf '%s\n' "$START" | awk -F': *' '/WaitTime/{print $2; exit}')
sleep 3
SHOT_AT=$(printf '%s\n' "$START" | awk -F': *' '/WaitTime/{print $2; exit}')
if [ -n "${TOTAL:-}" ] && [ "$TOTAL" != "0" ]; then
    pass "首屏耗时" "TotalTime=${TOTAL}ms WaitTime=${THIS_T:-n/a}ms"
else
    fail "首屏耗时" "am start -W 未给出 TotalTime（见 am-start.txt）"
fi
DISP=$(("$ADB" logcat -d -s ActivityTaskManager:I 2>/dev/null | grep -m1 "Displayed $PKG" | sed 's/.*Displayed //') || true)
[ -n "$DISP" ] && echo "logcat Displayed: $DISP" >> "$OUT/am-start.txt"
shot 00-home
dump 00-home

say "=== 1-3. 推股页 ==="
if ! tap_text nav "推股" contains && ! tap_text nav2 "推荐" contains; then
    fail "进入推股页" "底部导航里没找到「推股/推荐」入口，请手工截图后调整本脚本的选择器"
else
    say "  已进推股页，等深算 ${DEEP_WAIT}s（首轮全市场深算+趋势逐票取日K）"
    sleep "$DEEP_WAIT"
    dump 10-picks
    shot 10-picks
    N=$(python3 - "$OUT/10-picks.xml" <<'PY'
import sys, re, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: print(''); raise SystemExit
best = None
for e in root.iter('node'):
    for k in ('text', 'content-desc'):
        v = e.get(k) or ''
        m = re.search(r'(\d+)\s*只', v)
        if m:
            n = int(m.group(1))
            if n <= 30 and (best is None or n > best): best = n
print(best if best is not None else '')
PY
)
    TRENDS=$(count_text "$OUT/10-picks.xml" "多日趋势：")
    if [ -n "${N:-}" ] && [ "$N" -ge 6 ] 2>/dev/null; then
        pass "推股自动推荐" "名单 ${N} 只（>5，非仅前 5 只）；多日趋势条目 ${TRENDS} 条"
    elif [ -n "${N:-}" ] && [ "$N" -ge 1 ] 2>/dev/null; then
        fail "推股自动推荐" "名单只有 ${N} 只（≤5）：要么弱市无票达标，要么仍被前 5 只截断，需看 metadata"
    else
        fail "推股自动推荐" "没解析到「N 只」标题，可能仍在算或落在空态（截图 10-picks.png）"
    fi
    if [ "${TRENDS:-0}" -ge "${N:-99}" ] 2>/dev/null && [ "${TRENDS:-0}" -ge 6 ]; then
        pass "多日趋势覆盖" "${TRENDS} 条趋势 == 名单 ${N} 只，第 6 只起也算"
    else
        fail "多日趋势覆盖" "趋势 ${TRENDS} 条 vs 名单 ${N:-?} 只，未覆盖全名单"
    fi

    say "=== 5. 详情页技术指标 ==="
    dump 10-picks
    FIRST=$(python3 - "$OUT/10-picks.xml" <<'PY'
import sys, re, xml.etree.ElementTree as ET
try: root = ET.parse(sys.argv[1]).getroot()
except Exception: raise SystemExit
for n in root.iter('node'):
    t = (n.get('text') or '') + (n.get('content-desc') or '')
    if re.search(r'\b(0|3|6)\d{5}\b', t) and n.get('clickable') == 'true':
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds') or '')
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2)//2, (y1 + y2)//2); raise SystemExit
raise SystemExit
PY
)
    if [ -n "${FIRST:-}" ]; then
        # shellcheck disable=SC2086
        "$ADB" shell input tap $FIRST
        sleep 6
        dump 20-detail
        shot 20-detail
        HITS=$(has_any "$OUT/20-detail.xml" MACD KDJ RSI BOLL 均线 ATR)
        if [ "${HITS:-0}" -ge 3 ]; then
            pass "详情页技术指标" "命中 ${HITS} 类关键词（MACD/KDJ/RSI/BOLL/均线/ATR）"
        else
            fail "详情页技术指标" "只命中 ${HITS} 类，详情页未展示足够指标（20-detail.png）"
        fi
        "$ADB" shell input keyevent KEYCODE_BACK
        sleep 2
    else
        fail "详情页技术指标" "推股卡上没有可点的股票代码节点"
    fi

    say "=== 6. 断网空态 ==="
    "$ADB" logcat -c
    if tap_text fault "注入断网" exact; then
        tap_text recalc "重算" exact || say "  未找到「重算」，按钮可能需滚动"
        sleep "$DEEP_WAIT"
        dump 30-offline
        shot 30-offline
        if [ "$(has_any "$OUT/30-offline.xml" 失败 网络 不可用 空 离线 超时)" -ge 1 ]; then
            pass "断网空态" "界面给出失败/空态说明（30-offline.png）"
        else
            fail "断网空态" "注入断网后没看到失败说明"
        fi
    else
        fail "断网空态" "没找到「注入断网」按钮"
    fi

    say "=== 7. 限流空态 ==="
    if tap_text fault2 "注入限流" exact; then
        tap_text recalc2 "重算" exact || true
        sleep "$DEEP_WAIT"
        dump 40-ratelimit
        shot 40-ratelimit
        if [ "$(has_any "$OUT/40-ratelimit.xml" 限流 429 失败 不可用 空)" -ge 1 ]; then
            pass "限流空态" "界面给限流/失败说明（40-ratelimit.png）"
        else
            fail "限流空态" "注入限流后没看到限流说明"
        fi
        tap_text cancel "取消注入" exact || true
    else
        fail "限流空态" "没找到「注入限流」按钮"
    fi
fi

"$ADB" logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null

{
    echo "# skapp 真机六项验证"
    echo
    echo "- 时间：$(date '+%F %T')"
    echo "- 设备：$MODEL / API $SDK"
    echo "- 版本：$VCODE"
    echo "- 首屏：TotalTime=${TOTAL:-n/a}ms WaitTime=${THIS_T:-n/a}ms"
    echo
    echo "| 项目 | 结论 | 证据 |"
    echo "|---|---|---|"
    printf '%s\n' "${LINES[@]}"
    echo
    echo "截图与 dump 在同一目录：*.png / *.xml / logcat.txt / am-start.txt"
} > "$OUT/report.md"

say ""
say "=== 汇总：PASS $PASS / FAIL $FAIL ==="
say "报告：$OUT/report.md"
exit 0
