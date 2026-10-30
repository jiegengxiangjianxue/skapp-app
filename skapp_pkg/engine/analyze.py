"""算法+引擎集成入口：供 API 调用，把 algo-core 因子与 engine-core 融合逻辑接到实时数据上。
canonical 算法在 jiegengxiangjianxue/algo-core、engine-core；此处为集成层。
"""
from __future__ import annotations
import os, sys

HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
for sub in ("local/algo-core", "local/engine-core"):
    p = os.path.join(HERE, sub)
    if os.path.isdir(p) and p not in sys.path:
        sys.path.insert(0, p)

def ensure_canonical():
    """把 canonical 算法仓 clone/更新到 local/（Actions 与本地通用）"""
    base = os.path.join(HERE, "local")
    os.makedirs(base, exist_ok=True)
    import subprocess
    for repo in ("algo-core", "engine-core"):
        d = os.path.join(base, repo)
        if not os.path.isdir(d):
            subprocess.run(["git", "clone", "--depth", "1",
                            f"https://github.com/jiegengxiangjianxue/{repo}.git", d],
                           check=False, capture_output=True)

def _try_rust():
    try:
        sys.path.insert(0, os.path.join(HERE, "core", "target", "wheels"))
        import skapp_core as core  # pyo3 扩展（CI maturin 产物）
        return core
    except Exception:
        return None

def _ranks(vals):
    """截面 rank（升序归一 0..1），平值取平均名次；Rust 可用则加速"""
    core = _try_rust()
    if core and all(v == v for v in vals):  # 无 NaN 才走 Rust
        return core.cross_rank(vals)
    n = len(vals)
    if n <= 1:
        return [0.0] * n
    order = sorted(range(n), key=lambda i: vals[i])
    ranks = [0.0] * n
    i = 0
    while i < n:
        j = i
        while j + 1 < n and vals[order[j + 1]] == vals[order[i]]:
            j += 1
        avg = (i + j) / 2 / (n - 1)
        for k in range(i, j + 1):
            ranks[order[k]] = avg
        i = j + 1
    return ranks

def analyze(symbols: list[str]) -> dict:
    """对给定股票列表做引擎级分析：实时行情 + 截面 rank"""
    from skapp_pkg.datasrc.market_data import realtime_quotes
    quotes = realtime_quotes(symbols)
    if not quotes:
        return {"ts": 0, "engine": "orchestrator-lite", "items": []}
    ranks = _ranks([q["pct_chg"] for q in quotes])
    items = [{**q, "rank_chg": round(r, 4)} for q, r in zip(quotes, ranks)]
    return {"ts": max(q["ts"] for q in quotes), "engine": "orchestrator-lite",
            "items": sorted(items, key=lambda x: -x["rank_chg"])}
