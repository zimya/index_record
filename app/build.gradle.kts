plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.objectbox")
}

android {
    namespace = "me.melon.record"
    compileSdk = 34

    defaultConfig {
        applicationId = "me.melon.record"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
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

    implementation("io.coil-kt:coil-compose:2.6.0")

    // Material Icons Extended
    implementation("androidx.compose.material:material-icons-extended:1.6.0")

    // ObjectBox Browser
    debugImplementation("io.objectbox:objectbox-android-objectbrowser:3.7.1")

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
