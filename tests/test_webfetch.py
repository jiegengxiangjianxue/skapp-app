"""webfetch 模块测试：用本地 HTTP 服务验证伪装请求头、裁剪与阻断判定。"""
from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from skapp_pkg.datasrc import webfetch

ARTICLE = """<!doctype html><html><head>
<title>测试页面标题</title>
<meta name="description" content="页面描述">
<meta property="og:site_name" content="示例站">
<script type="application/ld+json">{"@type":"Article","headline":"示例"}</script>
<script src="/app.js"></script>
<style>body{color:red}</style>
</head><body>
<article>
  <h1>主标题</h1>
  <span class="price" data-v="12.50">¥12.50</span>
  <p>%s</p>
</article>
<table><tr><th>代码</th><th>价格</th></tr><tr><td>600519</td><td>1680</td></tr></table>
<a href="/next">下一页</a>
</body></html>"""


class _Handler(BaseHTTPRequestHandler):
    routes: dict = {}
    seen: list = []

    def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler 约定
        type(self).seen.append({k.lower(): v for k, v in self.headers.items()})
        status, ctype, body = type(self).routes.get(self.path.split("?")[0], (404, "text/plain", "nope"))
        raw = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *args):
        pass


@pytest.fixture(scope="module")
def site():
    handler = type("H", (_Handler,), {"routes": {
        "/article": (200, "text/html; charset=utf-8", ARTICLE % ("正文内容。" * 200)),
        "/api.json": (200, "application/json", json.dumps(
            {"items": [{"i": n, "pad": "x" * 600} for n in range(40)]})),
        "/challenge": (403, "text/html", "<html><body>Just a moment...</body></html>"),
        "/bare": (200, "text/html", '<html><body><div id="app"></div>'
                                    '<script src="/main.js"></script></body></html>'),
        # 国内站常见：响应头不带 charset，编码只写在 meta 里
        "/gbk": (200, "text/html", '<html><head><meta charset="gb2312"><title>贵州茅台</title>'
                                     '</head><body><p>行情数据</p></body></html>'.encode("gbk")),
    }, "seen": []})
    srv = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}", handler
    srv.shutdown()


@pytest.fixture(autouse=True)
def fast_and_clean(monkeypatch):
    monkeypatch.setattr(webfetch, "MIN_INTERVAL", 0.05)
    webfetch._rate_next.clear()
    yield
    webfetch._rate_next.clear()


def test_extract_fields():
    out = webfetch.extract(
        ARTICLE % ("正文内容。" * 200), base_url="https://x.test/",
        text_limit=50, include_links=True, include_tables=True,
        selectors={"price": ".price@data-v", "h1": "h1", "missing": ".nope"},
    )
    assert out["title"] == "测试页面标题"
    assert out["meta"]["description"] == "页面描述"
    assert out["meta"]["og:site_name"] == "示例站"
    assert out["picked"]["price"] == "12.50"
    assert out["picked"]["h1"] == "主标题"
    assert out["picked"]["missing"] is None
    assert out["text_len"] > 50 and out["text_truncated"] is True
    assert len(out["text"]) == 50
    assert out["json_ld"][0]["headline"] == "示例"
    assert out["links"][0]["href"] == "https://x.test/next"
    assert out["tables"][0][0] == ["代码", "价格"]
    assert out["needs_js"] is False


def test_masquerade_headers(site):
    base, handler = site
    out = webfetch.fetch(base + "/article")
    assert out["ok"] is True and out["status"] == 200
    assert out["profile"] == "chrome"
    sent = handler.seen[-1]
    assert "Windows NT 10.0" in sent["user-agent"]
    assert "Chrome/" in sent["user-agent"]
    assert sent["sec-ch-ua-platform"] == '"Windows"'
    assert sent["accept-language"].startswith("zh-CN")


def test_html_structured(site):
    base, _ = site
    out = webfetch.fetch_structured(base + "/article", text_limit=300,
                                    selectors={"price": ".price"})
    assert out["ok"] is True
    assert out["title"] == "测试页面标题"
    assert out["picked"]["price"] == "¥12.50"
    assert len(out["text"]) == 300
    assert "text" in out and "json" not in out


def test_challenge_is_blocked(site):
    base, _ = site
    out = webfetch.fetch(base + "/challenge")
    assert out["blocked"] == "challenge"
    assert out["ok"] is False


def test_json_trimmed(site):
    base, _ = site
    out = webfetch.fetch_structured(base + "/api.json")
    assert out["ok"] is True
    items = out["json"]["items"]
    assert len(items) == 30
    assert items[0]["pad"].endswith("…")


def test_spa_shell_flags_needs_js(site):
    base, _ = site
    out = webfetch.fetch_structured(base + "/bare")
    assert out["needs_js"] is True


def test_gbk_without_header_charset(site):
    base, _ = site
    out = webfetch.fetch_structured(base + "/gbk")
    assert out["title"] == "贵州茅台"
    assert "行情数据" in out["text"]


def test_rate_limit_waits(monkeypatch):
    monkeypatch.setattr(webfetch, "MIN_INTERVAL", 0.3)
    webfetch._rate_next.clear()
    webfetch._throttle("example.test")
    started = time.monotonic()
    waited = webfetch._throttle("example.test")
    assert waited > 0.2
    assert time.monotonic() - started >= 0.2


def test_rejects_bad_scheme():
    with pytest.raises(ValueError):
        webfetch.fetch("file:///etc/passwd")
