"""引擎选股集成：接入 canonical 引擎产物（engine_fusion / orchestrator / picks）。
数据优先级：本仓 data/engine/ 产物 > canonical 仓实时计算（需 local/ 下有 algo-core）。
只报告引擎输出的既有结果，不现场编造分析。
"""
from __future__ import annotations
import json, os

HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

ENGINE_FILES = [
    "data/engine/picks_latest.json",
    "data/engine/engine_fusion.json",
    "data/engine/decision.json",
]

def load_engine_output() -> dict | None:
    for rel in ENGINE_FILES:
        p = os.path.join(HERE, rel)
        if os.path.exists(p):
            try:
                return {"source": rel, "data": json.load(open(p, encoding="utf-8"))}
            except Exception:
                continue
    # canonical 引擎仓（local/ 由 ensure_canonical 拉取）
    local = os.path.join(HERE, "local", "engine-core", "engine_fusion.py")
    if os.path.exists(local):
        return {"source": "local/engine-core", "data": None}  # 需数据管道支持，占位
    return None

def picks(n: int = 10) -> dict:
    out = load_engine_output()
    if not out or not out.get("data"):
        return {"source": "none", "items": []}
    data = out["data"]
    # 兼容多种引擎产物结构：items / picks / top
    items = data.get("items") or data.get("picks") or data.get("top") or []
    if isinstance(items, dict):
        items = [{"symbol": k, **(v if isinstance(v, dict) else {"score": v})}
                 for k, v in list(items.items())[:n]]
    return {"source": out["source"], "items": items[:n]}
