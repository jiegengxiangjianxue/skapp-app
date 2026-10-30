"""实时数据源适配层：腾讯 / 新浪 / 东财（带新浪 fallback）。
限速铁律：腾讯 ≤4 并发、0.25s 间隔；501 切 ifzq.gtimg.cn。
sym 统一无点小写：sh600519 / sz000001 / hk00700。
"""
from __future__ import annotations
import json, re, time, urllib.request

UA = {"User-Agent": "Mozilla/5.0", "Referer": "finance.sina.com.cn"}

def _http(url: str, timeout: int = 10, headers: dict | None = None, encoding: str = "utf-8") -> str:
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode(encoding, errors="replace")

def norm(symbol: str) -> str:
    """统一为无点小写 shXXXXXX / hkXXXXX"""
    return symbol.replace(".", "").lower()

def _parse_qt(sym: str, fields: list[str], ts: int) -> dict | None:
    """腾讯 qt 响应解析；A 股与港股字段偏移不同"""
    if sym.startswith("hk"):
        if len(fields) < 6:
            return None
        return {
            "symbol": sym, "name": fields[1],
            "price": float(fields[3]), "prev_close": float(fields[4]),
            "open": float(fields[5]), "volume": float(fields[36]) if len(fields) > 36 and fields[36] else 0.0,
            "high": float(fields[33]) if len(fields) > 33 and fields[33] else 0.0,
            "low": float(fields[34]) if len(fields) > 34 and fields[34] else 0.0,
            "pct_chg": float(fields[32]) if len(fields) > 32 and fields[32] else 0.0,
            "ts": ts,
        }
    if len(fields) < 6:
        return None
    return {
        "symbol": sym, "name": fields[1],
        "price": float(fields[3]), "prev_close": float(fields[4]),
        "open": float(fields[5]), "volume": float(fields[6]) if fields[6] else 0.0,
        "high": float(fields[33]) if len(fields) > 33 and fields[33] else 0.0,
        "low": float(fields[34]) if len(fields) > 34 and fields[34] else 0.0,
        "pct_chg": float(fields[32]) if len(fields) > 32 and fields[32] else 0.0,
        "ts": ts,
    }

# ---------- 实时行情（腾讯 qt.gtimg.cn，A 股 + 港股，多票一次） ----------
def realtime_quotes(symbols: list[str]) -> list[dict]:
    syms = [norm(s) for s in symbols]
    url = "https://qt.gtimg.cn/q=" + ",".join(syms)
    text = _http(url, encoding="gbk")
    ts = int(time.time())
    out = []
    for line in text.strip().split(";"):
        line = line.strip()
        if not line or "=" not in line:
            continue
        sym = line.split("=")[0].split("_")[-1]
        fields = line.split("~")
        q = _parse_qt(sym, fields, ts)
        if q:
            out.append(q)
    return out

# ---------- 日线（新浪主源，含约 2011+ 全史） ----------
def daily_sina(symbol: str, scale: int = 240, datalen: int = 1023) -> list[dict]:
    sym = norm(symbol)
    url = (f"https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20d=/CN_MarketDataService.getKLineData"
           f"?symbol={sym}&scale={scale}&ma=no&datalen={datalen}")
    text = _http(url)
    m = re.search(r"\[.*\]", text, re.S)
    if not m:
        return []
    data = json.loads(m.group(0))
    return [{"symbol": sym, "date": d["day"], "open": float(d["open"]), "close": float(d["close"]),
             "high": float(d["high"]), "low": float(d["low"]), "volume": float(d["volume"])}
            for d in data]

# ---------- 全市场列表（新浪 hs_a，分页 100） ----------
def all_symbols(max_pages: int = 60) -> list[dict]:
    out, page = [], 0
    while page < max_pages:
        url = (f"https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
               f"Market_Center.getHQNodeData?page={page+1}&num=100&sort=symbol&asc=1&node=hs_a")
        try:
            data = json.loads(_http(url))
        except Exception:
            break
        if not data:
            break
        for d in data:
            out.append({"symbol": norm(d["symbol"]), "name": d["name"],
                        "price": float(d["trade"]), "pct_chg": float(d["changepercent"])})
        page += 1
        time.sleep(0.25)
    return out

# ---------- 消息/快讯（新浪 7x24，轻量兜底源） ----------
def news_feed(num: int = 30) -> list[dict]:
    url = f"https://zhibo.sina.com.cn/api/zhibo/feed?page=1&page_size={num}&zhibo_id=152&tag_id=0"
    try:
        d = json.loads(_http(url))
        items = d.get("result", {}).get("data", {}).get("feed", {}).get("list", [])
        return [{"ts": it.get("create_time", ""), "text": it.get("rich_text", "")} for it in items]
    except Exception:
        return []
