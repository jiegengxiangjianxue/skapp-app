// ── :engine —— 算法引擎 + 分析计算层（纯 Kotlin/JVM，不依赖 Android）──
// 拆分目的：引擎与分析逻辑可以脱离 App 单独编译、单独校验，
// 避免「UI 渲染改一行就要整包重编」和「引擎坏了却只看到 APK 构建成功」。
// 本模块不得 import android.* / androidx.*（CI 与本地均有守卫检查）。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // org.json 由 Android 运行时提供，compileOnly 避免与系统内置类重复打包
    compileOnly(libs.org.json)
}

// 守卫：引擎层一旦引入 Android 依赖，单独编译会失败——这正是我们要的早失败。
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
