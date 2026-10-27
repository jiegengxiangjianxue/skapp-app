# 发版规则（1.8.0 起生效）

出包位置：**GitHub Release**，不再上传 Actions artifact（artifact 需要登录 Actions 页面才能下，Release 资产可以直接下载到手机）。

## 每次发版必须做的事

1. **递增 `versionCode`，同步改 `versionName`**（`app/build.gradle.kts` defaultConfig）
   - `versionCode` 单调递增，绝不复用：Android 用它判重装/覆盖，重复会被判成「同版本」。
   - `versionName` 决定 Release tag（`v<versionName>`）。
2. 提交到 `main`，等 CI 五层门禁全绿：
   `engine-gate → analysis-gate → engine-kotlin → ui-render-gate → build`
3. build 阶段自动发布 Release：
   - tag / 标题：`v<versionName>`，资产名 `skapp-<versionName>-vc<versionCode>-arm64.apk`
   - **同版本重跑只覆盖资产**（`gh release upload --clobber`），不会堆重复 release
   - 因此「同版本号重复发版」= 资产被覆盖，用户可能拿到新包但版本号没变、系统不提示更新 → 必须升 versionCode

## 版本号与内容必须匹配

改代码就得升版本；只改文档/CI 不升也可以（不影响安装判断），但那样下次发版号会跳过，属正常。

## 模块边界（2026-10-27 起）

```
:app  —— MainActivity + AndroidManifest + res/（壳）
:ui   —— Compose 界面层：MainScreen / SkappViewModel / components / screens / GlassUi
         + 4 个 Android 侧 client（AiRouter / GitCodeAiClient / GitHubModelsClient / AllSourcesClient）
:engine —— 聚合层：只 api() 暴露下面四层，:ui / :app 的唯一入口（本身无源码）
  ↑
:engine-runtime —— 缓存 / 全市场筛选 / 盘前午盘推股产出（唯一允许「组合」的层）
  ↑
:engine-algo —— 指标 / 因子标定 / 12 引擎 / 情景与校准（纯计算）
  ↑
:engine-net  —— 云端 JSON、行情快照、日 K、资金流、消息抓取（只取数）
  ↑
:engine-core —— 数据模型 / 交易时段 / JSON 扁平化 / 进程健康核心（零依赖）
```

四层 + 聚合层一律禁止 `import android.* / androidx.*`，禁止依赖 `:ui`；依赖方向由 CI 脚本校验，反向依赖直接红。

守卫（CI 自动执行）：
- `engine-gate`：任一引擎层出现 `import android.` / `import androidx.` 直接失败；任一引擎层不得 `project(":ui")`；
  反向依赖（如 `:engine-algo` 依赖 `:engine-runtime`）由脚本校验
- `engine-kotlin`：按层构建 `:engine-core:test` → `:engine-net:compileKotlin` → `:engine-algo:test`
  → `:engine-runtime:test` → `:engine:test`（组合冒烟）；
  健康守卫的判定逻辑与测试必须存在（`engine-core/.../ProcessHealthCore*`），防被当死代码清掉；
  另有 `tools/org_json_api_guard.py` 黑名单模式守在编译后（逐层扫描四个模块的 class 目录）
- `ui-render-gate`：先 `:ui:compileDebugKotlin` 再 `:app:compileDebugKotlin`，UI 类型错误会报成 UI 错误而不是「APK 构建失败」
- `ui-render-gate`：`HealthGuard` 必须被 `MainActivity` 安装、且不得碰 `crash_*` / `crash_last.txt`（通道隔离）
- `ui-render-gate`：`tools/org_json_api_guard.py --android-jar <android.jar>` 白名单模式，
  把三层字节码里引用到的每个 org.json 方法与平台方法集逐项比对（vc19 闪退根因的守卫）

## 本地可做的验证（aarch64 容器，不能打 APK）

```bash
# 引擎分层验证（纯 JVM，按依赖顺序一层层过）
./gradlew :engine-core:test --offline
./gradlew :engine-net:compileKotlin --offline
./gradlew :engine-algo:test --offline
./gradlew :engine-runtime:test --offline
./gradlew :engine:test --offline          # 组合冒烟

# 界面层（library 模块，资源处理可用）
./gradlew :ui:compileDebugKotlin

# 壳层（aapt2 只有 x86_64 二进制，本地必须跳过资源任务）
./gradlew :app:compileDebugKotlin -x :app:processDebugResources

# org.json 越界守卫（本机有 android.jar 就能跑白名单模式）
python3 tools/org_json_api_guard.py --self-test
python3 tools/org_json_api_guard.py --android-jar /root/android-sdk/platforms/android-35/android.jar
```

## 凭据

`.git/config` 里**绝不内嵌 token**。推送用运行时注入：

```bash
TOK=$(python3 -c "import json,os;print(json.load(open(os.path.expanduser('~/.config/stocks-db/tokens.json')))['<account>'])")
AUTH=$(T="$TOK" python3 -c "import base64,os;print(base64.b64encode(('x-access-token:'+os.environ['T']).encode()).decode())")
git -c http.extraheader="Authorization: Basic $AUTH" push origin main
```
