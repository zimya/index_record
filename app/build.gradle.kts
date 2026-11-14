plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.objectbox")
    kotlin("kapt")
}

kapt {
    correctErrorTypes = true
}

android {
    namespace = "com.melonhu.indexrecord"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.melonhu.indexrecord"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.4.0"
        resourceConfigurations += listOf("zh", "zh-rCN", "en")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 【修复1】使用新版 API 解决 pickFirst 弃用警告
        jniLibs {
            pickFirsts.add("lib/**/libobjectbox-jni.so")
            useLegacyPackaging = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jsoup:jsoup:1.17.2")
        implementation("org.brotli:dec:0.1.2")

    // Markdown & Syntax & Math
    implementation("dev.snipme:highlights:1.1.0")
    implementation("io.github.erweixin:ratex-android:0.1.8")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-footnotes:0.24.0")

    // 使用应用内 MP4 extractor，避免系统 VideoView 无法解析部分 𝕏 GIF。
    implementation("androidx.media3:media3-exoplayer:1.3.1")


    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ObjectBox Browser
    debugImplementation("io.objectbox:objectbox-android-objectbrowser:3.7.1")

    testImplementation("junit:junit:4.13.2")
    // Exercise the exact Java projection used inside Reddit's hooked process (Android supplies org.json).
    testImplementation("org.json:json:20240303")

    // Xposed
    compileOnly(files("libs/api-82.jar"))
}

// 【修复2】解决 Configuration not found 问题
// 使用 afterEvaluate 确保在 Android 插件创建完配置后再执行排除逻辑
project.afterEvaluate {
    // 只有在 debug 模式下才排除 objectbox-android (因为 objectbrowser 自带了)
    // 防止类重复冲突
    configurations.findByName("debugRuntimeClasspath")?.exclude(group = "io.objectbox", module = "objectbox-android")
}
