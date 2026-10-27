// ── :engine-algo —— 算法 / 引擎层（指标、因子、12 引擎、情景与校准）──
// 只吃 K 线与标定参数，纯计算，不打网络、不缓存、不选股出单。
// 依赖方向：algo → net → core。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":engine-core"))
    api(project(":engine-net"))
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(libs.org.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.org.json)
}

tasks.test {
    useJUnitPlatform()
    // 真实 K 线 / 权重标定 fixture，验证「算法方向对不对」而不是只验证能跑
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
