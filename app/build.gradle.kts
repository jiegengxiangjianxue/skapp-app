plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.skapp.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.skapp.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 41
        versionName = "2.4.5"
    }

    // ── 固定签名 ──────────────────────────────────────────────────────
    // 之前没有 signingConfigs：CI 每次用 runner 新生成的 ~/.android/debug.keystore 签名，
    // 于是每个 release 的签名都不一样，手机上装新包必然报「签名冲突」（已实测）。
    // 这里把一把固定的 key 提交进私有仓，debug / release 都用它，签名从此稳定。
    // 口令随仓保存：本仓私有、产物仅自用；如需更严，改读环境变量 SKAPP_STORE_PASS。
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("keystore/skapp.jks")
            storePassword = System.getenv("SKAPP_STORE_PASS") ?: "skapp-stable-2026"
            keyAlias = "skapp"
            keyPassword = System.getenv("SKAPP_KEY_PASS") ?: "skapp-stable-2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 只出 arm64-v8a（用户要求：不要 x86）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
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
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":ui"))
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
}
