plugins {
    // AGP 9 以降は Kotlin サポートが組み込みなので kotlin-android プラグインは適用しない。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.infomaster"
    // 最新の androidx が API 37 でのコンパイルを要求するため 37。
    // targetSdk は実機に合わせて 36 のままでよい（新しい実行時挙動には乗らない）。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.infomaster"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // ダイジェストの取得先。リポジトリを変えたらここだけ直せばよい。
        buildConfigField(
            "String",
            "DIGEST_BASE_URL",
            "\"https://raw.githubusercontent.com/terada-artead/infomaster/main/digests/\"",
        )
    }

    buildTypes {
        release {
            // 自分用アプリなので難読化はせず、デバッグ署名のまま実機に入れられるようにする。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
