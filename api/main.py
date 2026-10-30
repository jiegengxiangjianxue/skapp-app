"""skapp REST API (FastAPI) -- 供 AI 接入做股票分析.
启动: uvicorn api.main:app --host 0.0.0.0 --port 8800
"""
from __future__ import annotations
import os, sys, json, datetime as dt
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

from skapp_pkg.datasrc.market_data import realtime_quotes, news_feed
from skapp_pkg.engine.analyze import analyze
from skapp_pkg.engine.picks import picks as engine_picks

app = FastAPI(title="skapp API", version="0.1.0")

class SymbolsBody(BaseModel):
    symbols: list[str]

def _snap(date: str):
    path = os.path.join(HERE, "data", "snapshots", f"{date}.json")
    if not os.path.exists(path):
        raise HTTPException(404, f"no snapshot for {date}")
    return json.load(open(path, encoding="utf-8"))

@app.get("/api/v1/quote/{symbol}")
def quote(symbol: str):
    """实时行情"""
    q = realtime_quotes([symbol])
    if not q:
        raise HTTPException(404, "no quote")
    return q[0]

@app.get("/api/v1/analysis/{symbol}")
def analysis(symbol: str):
    """单票引擎分析"""
    r = analyze([symbol])
    return r["items"][0] if r["items"] else {}

@app.post("/api/v1/analyze")
def analyze_batch(body: SymbolsBody):
    """批量分析 (AI 主入口)"""
    return analyze(body.symbols)

@app.get("/api/v1/picks")
def picks():
    """当日推股：优先引擎产物，缺省回落到今日快照"""
    ep = engine_picks()
    if ep.get("items"):
        return ep
    return _snap(dt.date.today().isoformat()).get("picks", {})

@app.get("/api/v1/engine/picks")
def engine_picks_api(n: int = 10):
    """引擎选股产物（engine_fusion / picks_latest）"""
    return engine_picks(n)

@app.get("/api/v1/review/{date}")
def review(date: str):
    return _snap(date).get("review", {})

@app.get("/api/v1/news")
def news(num: int = 30):
    return news_feed(num)

@app.get("/api/v1/snapshot/{date}")
def snapshot(date: str):
    return _snap(date)
