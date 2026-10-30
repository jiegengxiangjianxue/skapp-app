"""skapp 单元测试（python -m pytest tests/ -q）"""
import os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

def _skip(reason: str) -> None:
    """缺依赖时标 skip（而不是静默 return 冒充通过）"""
    try:
        import pytest
    except ImportError:
        print(f"SKIPPED: {reason}")
        return
    pytest.skip(reason)

def test_ranks_basic():
    from skapp_pkg.engine.analyze import _ranks
    assert _ranks([1.0, 2.0, 3.0]) == [0.0, 0.5, 1.0]

def test_ranks_ties():
    from skapp_pkg.engine.analyze import _ranks
    r = _ranks([5.0, 5.0, 1.0])
    assert r[0] == r[1] and r[2] < r[0]

def test_score():
    from skapp_pkg.pipeline.eod_sync import _score
    assert _score("公司回购股份，利好股东")["sentiment"] == "利好"
    assert _score("立案调查")["sentiment"] == "利空"
    assert _score("今天天气不错")["sentiment"] == "中性"

def test_review_settlement():
    from skapp_pkg.pipeline.eod_sync import _review_from_prev
    prev = {"picks": {"items": [{"symbol": "sh600519", "price": 100.0}]}}
    quotes = [{"symbol": "sh600519", "price": 102.0}]
    assert _review_from_prev(prev, quotes)[0]["ret_pct"] == 2.0

def test_review_uses_prev_quotes_fallback():
    """picks 项没 price 时（引擎产物只有 rank/symbol），用昨日快照 quotes 的收盘价作参考价"""
    from skapp_pkg.pipeline.eod_sync import _review_from_prev
    prev = {"picks": {"items": [{"symbol": "sh600519", "rank": 1}]},
            "quotes": [{"symbol": "sh600519", "price": 100.0}]}
    out = _review_from_prev(prev, [{"symbol": "sh600519", "price": 103.0}])
    assert out == [{"symbol": "sh600519", "ref_price": 100.0, "close": 103.0, "ret_pct": 3.0}]

def test_review_skips_missing_reference():
    """无参考价的票跳过，不把收益记成 0%"""
    from skapp_pkg.pipeline.eod_sync import _review_from_prev
    prev = {"picks": {"items": [{"symbol": "sh600519", "rank": 1}]}, "quotes": []}
    assert _review_from_prev(prev, [{"symbol": "sh600519", "price": 10.0}]) == []
    prev0 = {"picks": {"items": [{"symbol": "sh600519", "price": 0}]},
             "quotes": [{"symbol": "sh600519", "price": 0}]}
    assert _review_from_prev(prev0, [{"symbol": "sh600519", "price": 10.0}]) == []

def test_review_tolerates_dirty_quotes():
    """缺 price / 缺 symbol 的行情不能抛 KeyError"""
    from skapp_pkg.pipeline.eod_sync import _review_from_prev
    prev = {"picks": {"items": [{"symbol": "sh600519"}]}}
    assert _review_from_prev(prev, [{"symbol": "sh600519"}]) == []
    assert _review_from_prev(prev, [{"price": 10.0}]) == []

def test_review_prefers_ref_price():
    """方案 A：昨日 picks 项自带 ref_price（当日收盘）时优先用它，不再依赖昨日 quotes 覆盖"""
    from skapp_pkg.pipeline.eod_sync import _review_from_prev
    prev = {"picks": {"items": [{"rank": 1, "symbol": "sh600519", "ref_price": 100.0}]}, "quotes": []}
    out = _review_from_prev(prev, [{"symbol": "sh600519", "price": 104.0}])
    assert out == [{"symbol": "sh600519", "ref_price": 100.0, "close": 104.0, "ret_pct": 4.0}]

def test_picks_shape_unified():
    """引擎产物与自选股回落两种来源归一成同一组字段"""
    from skapp_pkg.pipeline.eod_sync import _build_picks
    qmap = {"sh600519": {"symbol": "sh600519", "name": "贵州茅台", "price": 100.0, "pct_chg": 1.5}}
    engine = {"source": "engine", "items": [{"rank": 1, "symbol": "sh600519"}]}
    fallback = None
    quotes = [qmap["sh600519"]]
    a, b = _build_picks(engine, quotes, qmap, "2026-10-30"), _build_picks(fallback, quotes, qmap, "2026-10-30")
    assert set(a["items"][0]) == set(b["items"][0]) == {"rank", "symbol", "name", "ref_price", "pct_chg"}
    assert a["items"][0] == {"rank": 1, "symbol": "sh600519", "name": "贵州茅台",
                            "ref_price": 100.0, "pct_chg": 1.5}
    assert a["source"] == "engine" and b["source"] == "watchlist_fallback" and "note" in b

def test_picks_ref_missing_is_reported():
    """取不到当日收盘的票 ref_price=None 且被列进 ref_missing（次日复盘会跳过，不冒充有参考价）"""
    from skapp_pkg.pipeline.eod_sync import _build_picks
    engine = {"source": "engine", "items": [{"rank": 1, "symbol": "sh600519"},
                                              {"rank": 2, "symbol": "sz000001"}]}
    picks = _build_picks(engine, [], {"sh600519": {"symbol": "sh600519", "price": 0}}, "2026-10-30")
    assert picks["items"][0]["ref_price"] is None
    assert picks["ref_missing"] == ["sh600519", "sz000001"]

def test_picks_marks_stale_engine_asof():
    """引擎产物来自 skapp-data 上一版时，asof 与 stale 必须写进快照（不拿旧当新）"""
    from skapp_pkg.pipeline.eod_sync import _build_picks
    engine = {"source": "composite_pool_v3", "asof": "2026-10-15", "items": [{"symbol": "sh600519", "rank": 1}]}
    old = _build_picks(engine, [], {}, "2026-10-30")
    assert old["engine_asof"] == "2026-10-15" and old["stale"] is True
    fresh = _build_picks(engine, [], {}, "2026-10-15")
    assert fresh["stale"] is False

def test_limit_up_thresholds():
    wheels = os.path.join(ROOT, "core", "target", "wheels")
    if os.path.isdir(wheels):
        sys.path.insert(0, wheels)
    try:
        import skapp_core
    except Exception as e:
        _skip(f"skapp_core 未构建（core/target/wheels 不存在）: {e}")
        return
    assert skapp_core.limit_up_pct("sh600519", False) == 10.0
    assert skapp_core.limit_up_pct("sz300750", False) == 20.0
    assert skapp_core.limit_up_pct("sh600519", True) == 5.0
