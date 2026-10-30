"""skapp v2 模块化联动引擎框架 (2026-10-22)。

现状标注：当前仓内无任何入口引用本文件（grep 实测 0 引用）。
central_pick 里 `from engine_registry import ENGINES, fuse_scores` 指向的模块在本仓不存在，
所以 picks 模块路径一调用就 ImportError；只当历史设计草稿，接入前需先落地 engine_registry。

原则: 每模块以本职引擎为最高权重, 其他引擎联动为次要权重; 推股模块由中枢独占
"""
from dataclasses import dataclass

@dataclass
class ModuleConfig:
    name: str
    primary: dict        # {engine: weight} 本职引擎 (高权重)
    secondary: dict      # {engine: weight} 联动引擎 (次要权重)

MODULES = {
    "news": ModuleConfig("消息", {"news_impact": 1.0},
                         {"sentiment": 0.4, "moneyflow": 0.3}),
    "pools": ModuleConfig("选股多池", {"orchestrator": 1.0},
                          {"factor117": 0.5, "ml_ensemble": 0.4, "lowpos_cyq": 0.4, "acc_detect": 0.3}),
    "sentiment": ModuleConfig("情绪", {"sentiment": 1.0, "ban_prob": 0.8},
                              {"calendar": 0.3}),
    "alert": ModuleConfig("异动", {"acc_detect": 1.0, "dual_trend": 0.7},
                          {"moneyflow": 0.4}),
    "mystic": ModuleConfig("玄学", {"calendar": 1.0, "ban_prob": 0.6},
                           {"sentiment": 0.3}),
    "picks": ModuleConfig("推股(中枢独占)", None, None),  # 特殊: 全引擎进中枢
}

def module_score(module: str, engine_scores: dict) -> list:
    """engine_scores: {engine: {sym: 0-1}} → 模块内加权融合
    模块主引擎权重高, 联动引擎次之"""
    cfg = MODULES[module]
    if cfg.primary is None:  # picks 模块走中枢
        return central_pick(engine_scores)
    total, src = {}, {}
    for eng, w in {**cfg.primary, **cfg.secondary}.items():
        weight = w
        for sym, s in engine_scores.get(eng, {}).items():
            total[sym] = total.get(sym, 0) + weight * s
            src.setdefault(sym, []).append(f"{eng}{'*' if eng in cfg.primary else ''}")
    ranked = sorted(total.items(), key=lambda x: -x[1])
    return [{"sym": s, "score": round(v, 3), "src": src[s],
             "primary_hit": any('*' in e for e in src[s])} for s, v in ranked[:20]]

def central_pick(engine_scores: dict, min_votes_high: int = 4) -> list:
    """推股中枢: 全部 12 引擎 → 加权 → 置信分级 → 中枢判断推不推"""
    from engine_registry import ENGINES, fuse_scores
    ranked = fuse_scores(engine_scores)
    out = []
    for item in ranked:
        n_engines = len(item["engines"])
        item["confidence"] = "high" if n_engines >= min_votes_high else ("mid" if n_engines >= 2 else "low")
        # 中枢闸门: 只有 high+mid 才推给用户
        if item["confidence"] in ("high", "mid"):
            out.append(item)
    return out[:10]
