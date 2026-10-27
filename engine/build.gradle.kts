// ── :engine —— 聚合层（对 :ui / :app 的唯一切口）──
//
// 拆分目的（用户诉求）：把「数据链路 / 算法引擎 / 产出组装」拆成能单独构建、单独验证的模块，
// 再一层层组合起来。依赖方向固定且单向：
//
//     :engine-core  ← 数据模型 / 交易时段 / JSON 扁平化 / 健康核心（零依赖）
//         ↑
//     :engine-net   ← 云端 JSON、行情快照、日 K、资金流、消息抓取
//         ↑
//     :engine-algo  ← 指标、因子标定、12 引擎、情景与校准（纯计算）
//         ↑
//     :engine-runtime ← 缓存、全市场筛选、盘前/午盘推股产出（唯一允许「组合」的层）
//         ↑
//     :engine       ← 本模块：只做聚合，用 api() 把下面四层原样暴露给 :ui
//
// 单独验证某一层：
//     ./gradlew :engine-core:test
//     ./gradlew :engine-net:compileKotlin
//     ./gradlew :engine-algo:test
//     ./gradlew :engine-runtime:test
//     ./gradlew :engine:test        // 组合冒烟：把各层串起来跑一遍
//
// 分层的强制力来自 Gradle 依赖本身：algo 想用 runtime 的缓存会直接「未解析引用」，编译期就挡住。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api 而非 implementation：:ui / :app 只依赖 :engine，仍要能看见四层的公开类型
    api(project(":engine-runtime"))

    testImplementation(kotlin("test"))
    testImplementation(libs.org.json)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("skapp.fixtures", rootProject.file("testdata").absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        showExceptions = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
