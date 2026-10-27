// ── :engine-runtime —— 运行/产出层（缓存、全市场筛选、盘前午盘推股产出）──
// 这一层才允许「组合」：把 net 的实时数据喂给 algo 的引擎，再组装成界面能用的产物。
// 依赖方向：runtime → algo → net → core。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":engine-core"))
    api(project(":engine-net"))
    api(project(":engine-algo"))
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
