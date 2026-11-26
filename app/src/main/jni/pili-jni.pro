# JNI resolves these names, including the Java-to-native symbol, by exact spelling.
-keepclassmembers class com.melonhu.indexrecord.hook.PiliPlusHook {
    private static native boolean nativeStart();
    static void onTlsBytes(long, boolean, byte[]);
    static void onTlsClosed(long);
}
-keepnames class com.melonhu.indexrecord.hook.PiliPlusHook
