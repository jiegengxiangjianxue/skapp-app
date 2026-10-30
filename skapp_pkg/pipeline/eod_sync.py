"""收盘后快照同步（Actions 调用入口）。
流程：生成当日快照（行情/消息分析/推股/推股复盘）→ 写 data/snapshots/{date}.json →
清理 data/live/ 当日明细，仅保留快照。幂等：同日重跑覆盖同 sha 文件。
复盘：若存在前一日快照，用今日收盘对昨日 picks 逐票结算。
参考价口径（方案 A）：快照写入时给每个 picks 项写 ref_price=该票当日收盘，
次日复盘直接取 ref_price，不再指望"昨日 quotes 恰好覆盖昨日 picks"。
"""
from __future__ import annotations
import json, math, os, shutil, datetime as dt

HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(HERE, "data")
PICKS_MAX = 10

def _num(v, default: float = 0.0) -> float:
    """数值兜底：缺失/NaN/inf/非数值回落 default（防 KeyError、防 NaN 传播到排序与除法）"""
    try:
        f = float(v)
    except (TypeError, ValueError):
        return default
    return f if math.isfinite(f) else default

def _positive(v) -> bool:
    """有效正数才可作参考价/分母"""
    return _num(v) > 0

def _score(text: str) -> dict:
    pos = ["利好", "上涨", "增持", "回购", "中标", "增长", "超预期", "分红"]
    neg = ["利空", "下跌", "减持", "处罚", "亏损", "警示", "立案", "退市"]
    p = sum(t in text for t in pos); n = sum(t in text for t in neg)
    label = "利好" if p > n else ("利空" if n > p else "中性")
    return {"text": text, "sentiment": label, "hits": p - n}

def _load_watchlist() -> dict:
    import yaml
    with open(os.path.join(HERE, "skapp_pkg", "config", "symbols.yaml"), encoding="utf-8") as f:
        cfg = yaml.safe_load(f)
    return {"watchlist": cfg.get("watchlist", []), "indices": cfg.get("indices", [])}

def _prev_snapshot(date: str) -> dict | None:
    snap_dir = os.path.join(DATA, "snapshots")
    if not os.path.isdir(snap_dir):
        return None
    dates = sorted(f[:-5] for f in os.listdir(snap_dir) if f.endswith(".json") and f[:-5] < date)
    if not dates:
        return None
    p = os.path.join(snap_dir, dates[-1] + ".json")
    try:
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None

def _engine_picks() -> dict | None:
    """读引擎产物；不存在或坏档都返回 None（交给自选股回落，不抛异常中断收盘任务）"""
    path = os.path.join(DATA, "engine", "picks_latest.json")
    if not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
    except Exception:
        return None
    return doc if isinstance(doc, dict) else None

def _norm_pick(item: dict, quote: dict | None, rank: int) -> dict:
    """把 picks 项归一成同一组字段，消除"引擎产物 {rank,symbol}"与"自选股整条行情"两种形状。

    ref_price 为方案 A 的参考价：当日收盘。取不到就是 None（次日复盘跳过该票，不拿别价顶替）。
    """
    q = quote or {}
    ref = _num(item.get("ref_price")) or _num(q.get("price"))
    return {
        "rank": rank,
        "symbol": item.get("symbol") or q.get("symbol"),
        "name": item.get("name") or q.get("name") or "",
        "ref_price": round(ref, 3) if ref > 0 else None,
        "pct_chg": round(_num(item.get("pct_chg"), _num(q.get("pct_chg"))), 2),
    }

def _build_picks(engine: dict | None, quotes: list[dict], quote_map: dict[str, dict], date: str) -> dict:
    if engine is not None:
        raw = [p for p in (engine.get("items") or []) if isinstance(p, dict) and p.get("symbol")]
        picks = {"source": engine.get("source") or "engine"}
        # 引擎产物来自 skapp-data 的上一版（本仓没有生产者），必须把产物日期带进快照，
        # 并标注 stale，避免把 10 天前的池子当今日新推
        asof = engine.get("asof") or engine.get("date")
        if asof:
            picks["engine_asof"] = str(asof)
            picks["stale"] = str(asof) < date
    else:
        # 引擎产物缺失时只能回落自选股涨幅前列，如实标注 provenance，不冒充全市场推股
        raw = sorted(quotes, key=lambda q: -_num(q.get("pct_chg")))
        picks = {"source": "watchlist_fallback",
                 "note": "引擎产物缺失（data/engine/picks_latest.json 不存在）：回落自选股涨幅前列，非全市场推股"}
    picks["items"] = [_norm_pick(p, quote_map.get(p.get("symbol")), i + 1) for i, p in enumerate(raw[:PICKS_MAX])]
    picks["ref_missing"] = [it["symbol"] for it in picks["items"] if it["ref_price"] is None]
    return picks

def _review_from_prev(prev: dict | None, quotes: list[dict]) -> list[dict]:
    """用今日收盘对昨日推股复盘。

    参考价优先级：昨日 picks 项自带 ref_price → price / close → 昨日快照 quotes 中该票收盘价。
    都取不到就跳过该票，不拿今日价顶替——顶替等于把收益硬记成 0%。
    """
    if not prev:
        return []
    close_px = {q.get("symbol"): _num(q.get("price")) for q in quotes
                if q.get("symbol") and _positive(q.get("price"))}
    prev_px = {q.get("symbol"): _num(q.get("price")) for q in (prev.get("quotes") or [])
               if q.get("symbol") and _positive(q.get("price"))}
    out = []
    for p in (prev.get("picks", {}) or {}).get("items", []):
        sym = p.get("symbol")
        close = close_px.get(sym)
        if not sym or close is None:
            continue
        ref = next((p[k] for k in ("ref_price", "price", "close")
                    if k in p and _positive(p[k])), None)
        if ref is None:
            ref = prev_px.get(sym)
        if ref is None:
            continue
        ret = (close - float(ref)) / float(ref) * 100
        out.append({"symbol": sym, "ref_price": float(ref), "close": close,
                    "ret_pct": round(ret, 2)})
    return out

def make_snapshot(date: str | None = None) -> dict:
    from skapp_pkg.datasrc.market_data import realtime_quotes, news_feed, norm
    cfg = _load_watchlist()
    date = date or dt.date.today().isoformat()
    watchlist = cfg["watchlist"]

    engine = _engine_picks()
    prev = _prev_snapshot(date)

    # 一次行情请求覆盖三方取价：自选股 + 今日推股 + 昨日推股（复盘要今日收盘）
    prev_syms = [p.get("symbol") for p in ((prev or {}).get("picks") or {}).get("items", []) if isinstance(p, dict)]
    want = [s for s in dict.fromkeys(list(watchlist) + [p.get("symbol") for p in ((engine or {}).get("items") or []) if isinstance(p, dict)] + prev_syms) if s]
    quote_map = {q["symbol"]: q for q in (realtime_quotes(want) or []) if q.get("symbol")}
    quotes = [quote_map[s] for s in (norm(w) for w in watchlist) if s in quote_map]

    scored = [_score(n["text"]) for n in news_feed(30)]

    return {
        "date": date, "ts": int(dt.datetime.now().timestamp()),
        "quotes": quotes,
        "news_analysis": scored,
        "picks": _build_picks(engine, quotes, quote_map, date),
        "review": {"items": _review_from_prev(prev, list(quote_map.values()))},
    }

def run(date: str | None = None):
    snap = make_snapshot(date)
    date = snap["date"]
    snap_dir = os.path.join(DATA, "snapshots")
    os.makedirs(snap_dir, exist_ok=True)
    path = os.path.join(snap_dir, f"{date}.json")
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(snap, f, ensure_ascii=False, default=str)
    os.replace(path + ".tmp", path)
    # 清理当日明细，仅保留快照
    live = os.path.join(DATA, "live")
    if os.path.isdir(live):
        shutil.rmtree(live, ignore_errors=True)
    print("snapshot written:", path, "| review items:", len(snap["review"]["items"]),
          "| picks:", len(snap["picks"]["items"]), "| ref_missing:", len(snap["picks"]["ref_missing"]))
    return path

if __name__ == "__main__":
    import sys
    run(sys.argv[1] if len(sys.argv) > 1 else None)
