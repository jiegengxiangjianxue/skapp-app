// ── :engine-core —— 数据模型 / 交易时段 / JSON 扁平化 / 进程健康核心 ──
// 分层契约（与 :engine-net / :engine-algo / :engine-runtime 一致）：
//   本模块不得依赖任何 Android、网络、协程调度以外的上层模块，只能被上层依赖。
//   违反会被编译直接挡住（依赖方向固定为 core → net → algo → runtime → :engine 聚合）。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // org.json 由 Android 运行时提供，compileOnly 避免与系统内置类重复打包
    compileOnly(libs.org.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.org.json)
}

tasks.test {
    useJUnitPlatform()
    // fixture 统一放在仓库根 testdata/，各层测试共用一份，不再各自抄一份
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
