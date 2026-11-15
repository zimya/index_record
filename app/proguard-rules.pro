# ==============================================================================
# 1. LibXposed (API 102) 框架与 Hook 入口
# ==============================================================================
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Hook 模块与组件保留
-keep class com.melonhu.indexrecord.hook.** { *; }
-keepclassmembers class com.melonhu.indexrecord.hook.** { *; }

# 宿主进程与主进程跨进程通信、队列与捕获客户端
-keep class com.melonhu.indexrecord.sync.** { *; }
-keepclassmembers class com.melonhu.indexrecord.sync.** { *; }

# ==============================================================================
# 2. ObjectBox 数据库核心规则
# ==============================================================================
-keep class io.objectbox.** { *; }
-keepclassmembers class io.objectbox.** { *; }
-dontwarn io.objectbox.**
-keep class *Cursor { *; }
-keep class *IdGetter { *; }
-keep class *Properties { *; }
-keep class *__* { *; }
-keep class io.objectbox.BoxStore { *; }
-keep class io.objectbox.ModelBuilder { *; }
-keep class io.objectbox.annotation.** { *; }

# 实体类（Entity）及其字段，避免数据库属性映射失效
-keep @io.objectbox.annotation.Entity class * { *; }
-keepclassmembers class * {
    @io.objectbox.annotation.Entity <fields>;
    @io.objectbox.annotation.Id <fields>;
    @io.objectbox.annotation.Index <fields>;
    @io.objectbox.annotation.Unique <fields>;
}
-keep class com.melonhu.indexrecord.data.model.** { *; }
-keepclassmembers class com.melonhu.indexrecord.data.model.** { *; }

# ==============================================================================
# 3. 数据解析与序列化 (Gson & Parser DTOs)
# ==============================================================================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Gson 核心与自定义 TypeAdapter
-keep class com.google.gson.** { *; }
-keepclassmembers class * implements com.google.gson.TypeAdapterFactory { *; }
-keepclassmembers class * extends com.google.gson.TypeAdapter { *; }

# 解析器内部用于 JSON 反序列化的结构模型
-keep class com.melonhu.indexrecord.parser.** { *; }
-keepclassmembers class com.melonhu.indexrecord.parser.** { *; }

# ==============================================================================
# 4. Native / JNI 接口
# ==============================================================================
-keepclasseswithmembernames class * {
    native <methods>;
}

# RaTeX 公式渲染引擎与 JNI 绑定
-dontwarn io.ratex.**
-keep class io.ratex.** { *; }
-keepclassmembers class io.ratex.** { *; }

# ==============================================================================
# 5. 通用 Android 与 Kotlin 保留规则
# ==============================================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 屏蔽第三方库的无害缺失类警告
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn androidx.media3.**
-dontwarn com.google.common.**
