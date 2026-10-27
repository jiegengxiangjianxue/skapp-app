# 运行健康守卫（vc19 起）

## 它补的是什么洞

`CrashGuard` 挂的是 `Thread.setDefaultUncaughtExceptionHandler`，只有「Java 层抛出且没人接」的异常
会经过它。以下三类**完全不抛异常**，进程直接消失，`crash_last.txt` / `crash_*.txt` 一条都不会有：

| 死法 | 是否有异常 | CrashGuard 能看到 | HealthGuard 能看到 |
|---|---|---|---|
| Java 未捕获异常 | 有 | ✅ | — |
| ANR（主线程卡死，系统强杀） | 无 | ❌ | ✅ 主线程心跳停顿 + 当场栈 |
| 低内存被 LMK 回收（SIGKILL） | 无 | ❌ | ✅ 内存水位留痕 |
| native 崩溃（SIGSEGV） | 无 | ❌ | 仅内存/停顿间接线索，栈仍需 tombstone（无 root 拿不到） |

## 两条不相干的抓手

**1. 主线程心跳（`skapp-health-watchdog` 线程）**

主线程每 500ms 自增一个计数（`Handler(mainLooper).postDelayed` 自循环）；看门狗线程盯着计数，
超过 5s 没跳就判定停顿，**当场**把主线程栈写盘。

- 阈值 5s = Android 输入事件 ANR 门槛：低于它会把正常长帧（图表重绘、首屏 18 个产物解析 ≈82ms 级的抖动）
  误报成 ANR；高于它又来不及在系统杀进程前落盘。
- 写在停顿**进行中**，不等进程被杀——ANR 被杀后什么都写不出来，这是这套东西的关键。
- 同一段停顿每 10s 补记一次，文件里 `episode: 1/2/3` 对应卡了 5s/15s/25s，卡死时长增长可见。

**2. 内存水位（`skapp-health-memory` 线程）**

启动后 8s（避开启动解析高峰）起，每 30s 采样：堆占用 / `Debug.getNativeHeapAllocatedSize()` /
`ActivityManager.MemoryInfo`（`availMem`/`threshold`/`lowMemory`）。

- 水位分级：<80% OK，≥80% WARN，≥92% CRITICAL；系统已报 `lowMemory` 时**一律 CRITICAL**
  （此时 LMK 随时动手，哪怕堆只用三成也必须记）。
- 只在「水位上升」或「同一水位已过 10 分钟」时落盘，避免每 30s 一个文件。

判定阈值与去重规则全部收敛在 `engine/.../health/ProcessHealthCore.kt` 的纯函数里，
由 `ProcessHealthCoreTest`（13 个断言）在 CI 断言——这是这套东西唯一容易写错的地方。

## 文件在哪、怎么发出来

| 位置 | 名字 | 用途 |
|---|---|---|
| `filesDir` | `health_<yyyyMMdd_HHmmss_SSS>.txt` | 单条记录，上限 20 个，超了删最老 |
| `filesDir` | `health_last.txt` | 最近一条，诊断页直接展示 |
| Download | `skapp_health_<...>.txt` | 下次启动自动导出（API 29+ 走 MediaStore） |

诊断页「运行健康（vc19 守卫）」卡片：实时水位 / 停顿次数 / 一键导出 / 看最近一条原文。

**通道隔离**：健康记录用 `health_` 前缀、独立导出循环，**不碰** `crash_*` 与红卡那份
`crash_last.txt`。2026-10-27 出过一次按前缀匹配把红卡文件导出后删掉的事故，所以两条证据链
任何一条都不许有能力删另一条的文件；CI 里有对应 grep 守卫。

## 守卫自身的可靠性

- 两个线程都是 daemon，不拖住进程退出；
- 每个循环体整段捕获 `Throwable`，写盘失败只记日志，绝不成为新的崩溃源；
- 记录文件上限 20 个，卡死时不会刷爆存储。

## 已覆盖的判定（回归用例见 `ProcessHealthCoreTest`）

- 阈值边界：4999ms 不记 / 5000ms 记；
- 同一段停顿不重复记；卡到 15s、25s 各补一条（episode 2、3）；
- 复报间隔配成 0 时不除零、不无限刷（退化为只记首次）；
- 内存分级：`used/max` 比例三档 + 系统 `lowMemory` 覆盖比例 + `maxBytes` 非法时不误判 CRITICAL；
- 栈截断 40 帧；报告字段（`kind/episode/blocked_ms/thread`）与小数点格式稳定（Locale.US）。
