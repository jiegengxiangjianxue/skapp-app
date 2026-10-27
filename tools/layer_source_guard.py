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

3. **界面层暂列观察名单**：`:ui` 还有历史遗留的云端调用点（决策页/池页等），
   本守卫对它们只报数量不失败——这些是「待迁移清单」，逐轮收敛，不许再增长。

## 为什么按文件路径而不是按包名

全 App 都是 `com.skapp.app.data` 这一个包（拆模块时刻意保留，避免 ui 里几十处 import 抖动），
所以只能靠**模块目录**判断层次。这也正是 CI 里能拦住的边界。
"""

from __future__ import annotations

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

    watch = [h for h in scan("SkappApi.") + scan("EngineFeed.") if h[0].startswith(WATCH_PREFIXES)]
    watch_files = sorted({rel for rel, _, _ in watch})

    if errors:
        print("::error::数据来源守卫未通过——分析链路不得直接吃云端快照/云端接口")
        for e in errors:
            print("  " + e)
        print("\n规则：云端只能经 engine-net/.../Db.kt 进入；实时数据走 LiveMarket。")
        return 1

    print(f"数据来源守卫通过：云端调用已收敛到 Db 层（{len(CLOUDFEED_ALLOWED)} 个文件）。")
    if watch_files:
        print(f"界面层待迁移云端调用点：{len(watch)} 处 / {len(watch_files)} 个文件（不失败，仅记录）：")
        for f in watch_files[:10]:
            print("  - " + f)
    return 0


if __name__ == "__main__":
    sys.exit(main())
