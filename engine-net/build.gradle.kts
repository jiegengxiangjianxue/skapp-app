// ── :engine-net —— 数据链路层（云端 JSON / 行情快照 / 日 K / 资金流 / 消息抓取）──
// 只做「把真实数据取回来 + 解析成模型」，不做任何选股判断。
// 依赖方向：net → core，不得反向依赖 algo / runtime。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":engine-core"))
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(libs.org.json)

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
