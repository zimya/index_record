package com.melonhu.indexrecord.hook;

/**
 * Interface implemented by all platform-specific hooks.
 */
public interface PlatformHook {
    void init(HookContext context, ClassLoader classLoader);
}
