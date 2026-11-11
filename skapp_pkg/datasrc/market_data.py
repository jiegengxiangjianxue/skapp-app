"""实时数据源适配层：腾讯 / 新浪 / 东财（带新浪 fallback）。
限速铁律：腾讯 ≤4 并发、0.25s 间隔；501 切 ifzq.gtimg.cn。
sym 统一无点小写：sh600519 / sz000001 / hk00700。
"""
from __future__ import annotations

import json
import math
import re
import time
import urllib.request

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
    base = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center."
    headers = {**UA, "Referer": "https://finance.sina.com.cn/"}
    out = {}
    try:
        count = json.loads(_http(base + "getHQNodeStockCount?node=hs_a", headers=headers))
        if isinstance(count, bool) or not isinstance(count, (str, int, float)):
            raise ValueError("invalid symbol count")
        expected = int(count)
        if expected < 3000 or (isinstance(count, float) and count != expected):
            raise ValueError("invalid symbol count")
        for page in range(1, max_pages + 1):
            time.sleep(0.7)
            url = base + f"getHQNodeData?page={page}&num=100&sort=symbol&asc=1&node=hs_a"
            data = json.loads(_http(url, headers=headers))
            if not isinstance(data, list) or not data:
                raise ValueError(f"invalid or empty symbol page {page}")
            for d in data:
                if not isinstance(d, dict) or not isinstance(d.get("symbol"), str):
                    raise ValueError(f"invalid symbol row on page {page}")
                symbol = norm(d["symbol"])
                name = d.get("name")
                if not re.fullmatch(r"(?:sh|sz|bj)[0-9]{6}", symbol) or not isinstance(name, str) or not name.strip():
                    raise ValueError(f"invalid symbol or name on page {page}")
                row = {"symbol": symbol, "name": name.strip()}
                for field, source in (("price", "trade"), ("pct_chg", "changepercent")):
                    try:
                        value = float(d.get(source))
                    except (TypeError, ValueError, OverflowError):
                        value = None
                    row[field] = value if value is not None and math.isfinite(value) else None
                if symbol in out and out[symbol] != row:
                    raise ValueError(f"conflicting duplicate symbol on page {page}")
                out[symbol] = row
            if len(out) >= expected or len(data) < 100:
                break
        if len(out) != expected or not {"sh", "sz"}.issubset({s[:2] for s in out}):
            raise ValueError(f"incomplete symbol table: expected {expected}, got {len(out)} unique symbols")
    except Exception as exc:
        raise RuntimeError(f"Sina symbol table rejected: {exc}") from exc
    return list(out.values())

# ---------- 消息/快讯（新浪 7x24，轻量兜底源） ----------
def news_feed(num: int = 30) -> list[dict]:
    url = f"https://zhibo.sina.com.cn/api/zhibo/feed?page=1&page_size={num}&zhibo_id=152&tag_id=0"
    try:
        d = json.loads(_http(url))
        items = d.get("result", {}).get("data", {}).get("feed", {}).get("list", [])
        return [{"ts": it.get("create_time", ""), "text": it.get("rich_text", "")} for it in items]
    except Exception:
        return []
