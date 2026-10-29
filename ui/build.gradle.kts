// ── :ui —— Compose 界面层（Android library）──
// 拆分目的：界面改一行不再牵动打包/签名/ABI，也不再把「UI 编译失败」混在 APK 构建失败里。
// 依赖方向固定为 :app → :ui → :engine，:ui 不得反向依赖 :app。
//
// 2026-10-27 拆分实测前提：:app 的 ui 包对 R.* 零引用（grep 无命中），
// 因此界面层不需要资源，搬进 library 模块不涉及资源合并。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.skapp.app.ui"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    buildFeatures { compose = true }
    // Robolectric 渲染测试需要读 Android 资源（主题/排版/字体度量），否则 compose 里
    // LocalContext / 资源查找在单测环境直接抛。
    testOptions { unitTests { isIncludeAndroidResources = true } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // ── 屏渲染测试（JVM 上跑，不需要模拟器）──
    // 容器/CI 里没有 emulator、也没有 root/Shizuku，真机截图做不到；Robolectric 能在 JVM 里
    // 提供 Android 运行时 + Compose 组合，让「这一屏能不能渲染出预期文案」变成可断言的事。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test:core:1.6.1")
}
