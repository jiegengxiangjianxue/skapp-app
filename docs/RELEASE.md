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
:engine —— 纯 Kotlin/JVM 引擎与分析层，禁止 import android.* / androidx.*，禁止依赖 :ui
```

守卫（CI 自动执行）：
- `engine-gate`：`:engine` 出现 `import android.` / `import androidx.` 直接失败；`:engine` 不得 `project(":ui")`
- `ui-render-gate`：先 `:ui:compileDebugKotlin` 再 `:app:compileDebugKotlin`，UI 类型错误会报成 UI 错误而不是「APK 构建失败」

## 本地可做的验证（aarch64 容器，不能打 APK）

```bash
# 引擎（纯 JVM，完整编译）
./gradlew :engine:compileKotlin --offline

# 界面层（library 模块，资源处理可用）
./gradlew :ui:compileDebugKotlin

# 壳层（aapt2 只有 x86_64 二进制，本地必须跳过资源任务）
./gradlew :app:compileDebugKotlin -x :app:processDebugResources
```

## 凭据

`.git/config` 里**绝不内嵌 token**。推送用运行时注入：

```bash
TOK=$(python3 -c "import json,os;print(json.load(open(os.path.expanduser('~/.config/stocks-db/tokens.json')))['<account>'])")
AUTH=$(T="$TOK" python3 -c "import base64,os;print(base64.b64encode(('x-access-token:'+os.environ['T']).encode()).decode())")
git -c http.extraheader="Authorization: Basic $AUTH" push origin main
```
