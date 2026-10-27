#!/usr/bin/env python3
"""数据来源守卫：分析链路只吃实时数据，云端只能经「数据库层」进。

## 规则（来自明确的产品约束）

> 本地实时获取实时分析，不要使用快照和云端。只有数据库可以调用云端。

落地成三条可检规则：

1. **云端只能从 Db 进门**：`CloudFeed.` 只允许出现在
   `engine-net/.../CloudFeed.kt`（实现）与 `engine-net/.../Db.kt`（门）。
   其它任何文件出现 `CloudFeed.` 就算违规——说明有人绕过库层直接吃云端快照。

2. **算法/产出层不得直接打云端接口**：`engine-algo` / `engine-runtime` 里出现
   `SkappApi.` 即违规。取数走 `LiveMarket`（实时）或 `Db`（库表）。

3. **界面层不许有云端调用**（第二轮起硬性）：`:ui` / `:app` 里出现
   `SkappApi.xxx()` / `SiteBoardClient.xxx()` / `EngineFeed.loadAll(` / `SmartMoney.fetch(`
   即失败。界面要么读库表门面 `DbTables`（归档/配置表），要么读实时层
   （`LiveMarket` / `LiveQuote` / `LivePulse` / `LocalScreener` / `LocalSnapshot` / `LocalSectors`）。

   **只统计调用，不统计类型引用**：`SiteBoardClient.Pool`、`EngineFeed.EngineV5Top`
   这类只是界面用来渲染的数据类型，不属于「取数点」。早先按 `EngineFeed.` 子串统计，
   把 EnginesScreen 里 10 处类型标注也算成云端调用，数字虚高、迁移方向被带偏。

## 为什么按文件路径而不是按包名

全 App 都是 `com.skapp.app.data` 这一个包（拆模块时刻意保留，避免 ui 里几十处 import 抖动），
所以只能靠**模块目录**判断层次。这也正是 CI 里能拦住的边界。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 允许出现 CloudFeed. 的文件（相对仓库根）
CLOUDFEED_ALLOWED = {
    "engine-net/src/main/java/com/skapp/app/data/CloudFeed.kt",
    "engine-net/src/main/java/com/skapp/app/data/Db.kt",
}

# 允许直接调用 SkappApi 的模块前缀（net 是取数层本身）
SKAPPAPI_ALLOWED_PREFIXES = ("engine-net/",)

# 观察名单：只报数量，不失败
WATCH_PREFIXES = ("ui/", "app/")

SKIP_PARTS = ("/build/", "/.git/")


def kt_files(*prefixes: str):
    for prefix in prefixes:
        base = ROOT / prefix
        if not base.exists():
            continue
        for path in base.rglob("*.kt"):
            rel = path.relative_to(ROOT).as_posix()
            if any(p in f"/{rel}" for p in SKIP_PARTS):
                continue
            yield rel, path


def scan(needle: str):
    hits = []
    for rel, path in kt_files("engine-core", "engine-net", "engine-algo", "engine-runtime", "ui", "app"):
        for i, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            if needle in line and not line.lstrip().startswith(("//", "*", "/*")):
                hits.append((rel, i, line.strip()))
    return hits


def main() -> int:
    errors: list[str] = []

    for rel, line_no, line in scan("CloudFeed."):
        if rel in CLOUDFEED_ALLOWED:
            continue
        errors.append(f"{rel}:{line_no} 绕过数据库层直接读云端：{line}")

    for rel, line_no, line in scan("SkappApi."):
        if rel.startswith(SKAPPAPI_ALLOWED_PREFIXES):
            continue
        if rel.startswith(WATCH_PREFIXES):
            continue
        errors.append(f"{rel}:{line_no} 分析层直连云端接口（应走 LiveMarket / Db）：{line}")

    # ── 规则 3：界面层不得直接调云端 ──
    UI_CLOUD_NEEDLES = ("SkappApi.", "SiteBoardClient.", "EngineFeed.loadAll(", "SmartMoney.fetch(")
    # 仅作为「类型」出现时不算取数点（界面要渲染这些结构体）
    TYPE_NAMES = {"Pool", "PoolItem"}

    ui_calls = []
    for needle in UI_CLOUD_NEEDLES:
        for rel, line_no, line in scan(needle):
            if not rel.startswith(WATCH_PREFIXES):
                continue
            members = set(re.findall(r"SiteBoardClient\.(\w+)", line))
            if members and members <= TYPE_NAMES:
                continue
            ui_calls.append((rel, line_no, line))

    ui_files = sorted({rel for rel, _, _ in ui_calls})
    for rel, line_no, line in ui_calls:
        errors.append(f"{rel}:{line_no} 界面层直接调云端（应走 DbTables / 实时层）：{line}")

    if errors:
        print("::error::数据来源守卫未通过——分析链路不得直接吃云端快照/云端接口")
        for e in errors:
            print("  " + e)
        print("\n规则：云端只能经 engine-net/.../Db.kt 进入；实时数据走 LiveMarket。")
        return 1

    print(f"数据来源守卫通过：云端调用已收敛到 Db 层（{len(CLOUDFEED_ALLOWED)} 个文件）。")
    print(f"界面层云端调用点：{len(ui_calls)} 处（0 为达标）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
