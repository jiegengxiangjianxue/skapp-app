# 架构决策记录（ADR）

记录「为什么这么定」的决策，避免同一问题反复返工。编号连续，一条决策一个文件，**已生效的决策不删改，推翻就新增一条并标注 supersedes**。

| 编号 | 决策 | 状态 | 日期 |
|---|---|---|---|
| [0001](0001-cloud-python-vs-ondevice-kotlin.md) | 云端 Python 重计算 / 端上 Kotlin 轻量应用的分工（含上 Rust 触发条件） | Accepted | 2026-10-27 |

## 命名与格式

- 文件名：`NNNN-短横线小写标题.md`
- 必含段落：状态 / 日期 / 背景 / 决策 / 后果 / 参考（性能类决策须带**可量化触发条件**）
- 状态取值：`Proposed` / `Accepted` / `Superseded by ADR-NNNN` / `Deprecated`
