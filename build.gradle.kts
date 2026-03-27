// build.gradle.kts
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // 在这里指定 ObjectBox 版本
        classpath("io.objectbox:objectbox-gradle-plugin:3.7.1")
    }
}
// 这里只声明插件的版本，apply false 表示不在根目录应用，但在子模块中可用
plugins {
    id("com.android.application") version "8.8.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
