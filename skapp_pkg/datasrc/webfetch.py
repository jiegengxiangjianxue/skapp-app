"""通用网页抓取：伪装为 Windows x64 + Chrome，输出裁剪后的结构化结果。

伪装只做到 HTTP 层：curl_cffi 复刻 Chrome 的 TLS/JA3、HTTP/2 帧序与默认请求头
（User-Agent 为 Windows NT 10.0 + Chrome，Sec-Ch-Ua-Platform: "Windows"），
不模拟 JS 环境（navigator/Canvas/WebGL），需要 JS 执行的站点返回 needs_js 提示。
遇到 WAF / 人机验证只记 blocked，不绕过、不轮换代理。
限速：同域最小间隔 0.7s；单页上限 5MB；输出默认裁剪，不把整页原文带出。
"""
from __future__ import annotations

import json
import re
import threading
import time
import urllib.parse

from bs4 import BeautifulSoup
from curl_cffi import requests as cf_requests

PROFILE = "chrome"
MIN_INTERVAL = 0.7
MAX_BYTES = 5_000_000
DEFAULT_LOCALE = "zh-CN"

_META_KEEP = {
    "description", "keywords", "author", "article:published_time",
    "og:title", "og:description", "og:site_name", "og:type",
}

# 强标记：任何状态码出现都视为验证页
_CHALLENGE_STRONG = (
    "just a moment", "cf-chl", "cf_chl_opt", "checking your browser",
    "enable javascript and cookies to continue", "人机验证", "请完成验证", "滑动验证",
)
# 弱标记：仅在 4xx/5xx 上才判定，避免正文里出现单词被误伤
_CHALLENGE_WEAK = ("captcha", "access denied", "security check", "安全验证")

_rate_lock = threading.Lock()
_rate_next: dict[str, float] = {}
_local = threading.local()


def _session() -> cf_requests.Session:
    """线程内复用 Session：保持 Chrome 指纹与连接池，避免每次重新握手。"""
    s = getattr(_local, "session", None)
    if s is None:
        s = cf_requests.Session(impersonate=PROFILE)
        _local.session = s
    return s


def _throttle(netloc: str) -> float:
    """同域串行限速，返回实际等待秒数。"""
    with _rate_lock:
        now = time.monotonic()
        ready_at = _rate_next.get(netloc, 0.0)
        wait = max(0.0, ready_at - now)
        _rate_next[netloc] = max(now, ready_at) + MIN_INTERVAL
    if wait > 0:
        time.sleep(wait)
    return wait


def _classify_block(status: int, text: str) -> str | None:
    head = text[:4000].lower()
    if status == 429:
        return "rate_limited"
    if any(m in head for m in _CHALLENGE_STRONG):
        return "challenge"
    if status >= 400 and any(m in head for m in _CHALLENGE_WEAK):
        return "challenge"
    if status == 403:
        return "forbidden"
    return None


def _read_body(resp, max_bytes: int) -> tuple[bytes, bool]:
    buf = bytearray()
    truncated = False
    for chunk in resp.iter_content():
        if not chunk:
            continue
        buf.extend(chunk)
        if len(buf) >= max_bytes:
            truncated = True
            break
    return bytes(buf[:max_bytes]), truncated


def _sniff_meta_charset(body: bytes) -> str | None:
    m = re.search(rb'charset=["\']?([\w-]+)', body[:4096], re.I)
    return m.group(1).decode("ascii", "ignore").lower() if m else None


def _decode(body: bytes, content_type: str) -> str:
    """头声明的编码 > utf-8 严格校验 > 页面 meta 声明 > gb18030 兜底。

    国内站常见响应头只有 text/html、真正编码写在 meta（gb2312），
    只看头会在 utf-8 下解出乱码。
    """
    m = re.search(r"charset=([\w-]+)", content_type or "", re.I)
    header_enc = (m.group(1) if m else "").lower()
    candidates: list[str] = []
    if header_enc:
        candidates.append(header_enc)
        if header_enc in ("gb2312", "gbk"):
            candidates.append("gb18030")
    try:
        body.decode("utf-8")
        candidates.append("utf-8")
    except UnicodeDecodeError:
        meta_enc = _sniff_meta_charset(body)
        if meta_enc:
            candidates.append("gb18030" if meta_enc in ("gb2312", "gbk") else meta_enc)
        candidates.append("gb18030")
    for enc in dict.fromkeys(candidates):
        try:
            return body.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return body.decode("utf-8", errors="replace")


def fetch(url: str, *, method: str = "GET", data=None, json_body=None,
          headers: dict | None = None, referer: str | None = None,
          locale: str = DEFAULT_LOCALE, timeout: int = 20,
          max_bytes: int = MAX_BYTES, retries: int = 1) -> dict:
    """单次抓取，返回状态与解码后的正文（受 max_bytes 限制）。

    retries 只覆盖网络层错误；4xx/5xx 不重试，避免对目标站加压。
    """
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https"):
        raise ValueError(f"unsupported scheme: {parts.scheme or '(empty)'}")

    hdrs = {"Accept-Language": f"{locale},{locale.split('-')[0]};q=0.9"}
    if referer:
        hdrs["Referer"] = referer
    hdrs.update(headers or {})

    waited = _throttle(parts.netloc)
    started = time.perf_counter()
    attempt = 0
    while True:
        try:
            resp = _session().request(
                method.upper(), url, headers=hdrs, data=data, json=json_body,
                timeout=timeout, stream=True, allow_redirects=True,
            )
            break
        except cf_requests.RequestsError as exc:
            if attempt >= retries:
                return {
                    "ok": False, "status": 0, "url": url, "final_url": url,
                    "content_type": "", "bytes": 0,
                    "elapsed_ms": int((time.perf_counter() - started) * 1000),
                    "waited_ms": int(waited * 1000), "profile": PROFILE,
                    "truncated": False, "blocked": None,
                    "error": f"{type(exc).__name__}: {str(exc)[:200]}", "text": "",
                }
            attempt += 1
            time.sleep(0.8 * attempt)

    try:
        body, truncated = _read_body(resp, max_bytes)
        status = resp.status_code
        final_url = str(resp.url)
        ctype = resp.headers.get("content-type", "") or ""
    finally:
        resp.close()

    text = _decode(body, ctype)
    blocked = _classify_block(status, text)
    return {
        "ok": blocked is None and status < 400,
        "status": status,
        "url": url,
        "final_url": final_url,
        "content_type": ctype,
        "bytes": len(body),
        "elapsed_ms": int((time.perf_counter() - started) * 1000),
        "waited_ms": int(waited * 1000),
        "profile": PROFILE,
        "truncated": truncated,
        "blocked": blocked,
        "text": text,
    }


def extract(html: str, *, base_url: str = "", text_limit: int = 1500,
            include_links: bool = False, include_tables: bool = False,
            selectors: dict[str, str] | None = None, max_items: int = 30) -> dict:
    """HTML → 结构化字段。selectors 语法：'css' 取值文本，'css@attr' 取属性。"""
    soup = BeautifulSoup(html, "html.parser")

    json_ld: list = []
    for node in soup.find_all("script", attrs={"type": "application/ld+json"}):
        try:
            obj = json.loads(node.string or "null")
        except (ValueError, TypeError):
            continue
        if obj is None:
            continue
        json_ld.append(obj if not isinstance(obj, list) else obj[:max_items])
        if len(json_ld) >= 3:
            break

    has_external_script = bool(soup.find("script", src=True))

    for tag in soup(["script", "style", "noscript", "svg", "template", "iframe"]):
        tag.decompose()

    title = soup.title.get_text(strip=True) if soup.title else ""
    meta: dict[str, str] = {}
    for node in soup.find_all("meta"):
        key = (node.get("name") or node.get("property") or "").lower()
        if key in _META_KEEP and node.get("content"):
            meta[key] = node["content"][:300]

    root = soup.find("article") or soup.find("main") or soup.body or soup
    full_text = " ".join(root.get_text(" ", strip=True).split())
    text_len = len(full_text)

    picked: dict[str, object] = {}
    for name, spec in (selectors or {}).items():
        css, _, attr = spec.partition("@")
        try:
            node = soup.select_one(css.strip())
        except Exception as exc:  # css 写错时只影响该字段
            picked[name] = {"error": f"bad selector: {exc}"[:200]}
            continue
        if node is None:
            picked[name] = None
        elif attr:
            picked[name] = node.get(attr.strip())
        else:
            picked[name] = " ".join(node.get_text(" ", strip=True).split())[:500]

    out: dict[str, object] = {
        "title": title[:300],
        "meta": meta,
        "text": full_text[:text_limit],
        "text_len": text_len,
        "text_truncated": text_len > text_limit,
    }
    if json_ld:
        out["json_ld"] = json_ld
    if picked:
        out["picked"] = picked
    if include_links:
        links = []
        for a in soup.find_all("a", href=True)[:max_items]:
            links.append({
                "text": a.get_text(" ", strip=True)[:80],
                "href": urllib.parse.urljoin(base_url, a["href"]),
            })
        out["links"] = links
    if include_tables:
        tables = []
        for table in soup.find_all("table")[:3]:
            rows = []
            for tr in table.find_all("tr")[:12]:
                cells = [c.get_text(" ", strip=True)[:60] for c in tr.find_all(["th", "td"])][:8]
                if cells:
                    rows.append(cells)
            if rows:
                tables.append(rows)
        out["tables"] = tables

    # 正文极少但页面挂着一堆外链脚本，通常是 SPA 空壳，需要 JS 渲染
    out["needs_js"] = text_len < 200 and has_external_script
    return out


def _trim_json(obj, max_items: int = 30, depth: int = 0):
    if depth > 4:
        return "…"
    if isinstance(obj, list):
        return [_trim_json(x, max_items, depth + 1) for x in obj[:max_items]]
    if isinstance(obj, dict):
        return {k: _trim_json(v, max_items, depth + 1) for k, v in list(obj.items())[:max_items]}
    if isinstance(obj, str) and len(obj) > 500:
        return obj[:500] + "…"
    return obj


def fetch_structured(url: str, *, selectors: dict[str, str] | None = None,
                     text_limit: int = 1500, include_links: bool = False,
                     include_tables: bool = False, **fetch_kw) -> dict:
    """抓取并按类型裁剪：JSON 接口返回截断后的对象，HTML 返回抽取字段。"""
    raw = fetch(url, **fetch_kw)
    ctype = raw.pop("content_type", "") or ""
    body = raw.pop("text", "") or ""
    out = {**raw, "content_type": ctype}
    if raw.get("error"):
        return out

    lowered = ctype.lower()
    if "json" in lowered:
        try:
            out["json"] = _trim_json(json.loads(body))
        except ValueError:
            out["json"] = None
            out["ok"] = False
            out["error"] = "content-type says json but body did not parse"
        return out

    if "html" not in lowered and "xml" not in lowered:
        out["text"] = body[:text_limit]
        out["text_len"] = len(body)
        out["text_truncated"] = len(body) > text_limit
        return out

    out.update(extract(
        body, base_url=raw.get("final_url") or url, text_limit=text_limit,
        include_links=include_links, include_tables=include_tables,
        selectors=selectors,
    ))
    return out
