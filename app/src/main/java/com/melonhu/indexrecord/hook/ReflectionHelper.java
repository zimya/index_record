package com.melonhu.indexrecord.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.github.libxposed.api.XposedInterface;

public final class ReflectionHelper {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = new HashMap<>();

    static {
        PRIMITIVE_WRAPPERS.put(boolean.class, Boolean.class);
        PRIMITIVE_WRAPPERS.put(byte.class, Byte.class);
        PRIMITIVE_WRAPPERS.put(char.class, Character.class);
        PRIMITIVE_WRAPPERS.put(short.class, Short.class);
        PRIMITIVE_WRAPPERS.put(int.class, Integer.class);
        PRIMITIVE_WRAPPERS.put(long.class, Long.class);
        PRIMITIVE_WRAPPERS.put(float.class, Float.class);
        PRIMITIVE_WRAPPERS.put(double.class, Double.class);
        PRIMITIVE_WRAPPERS.put(void.class, Void.class);
    }

    private ReflectionHelper() {}

    public static Class<?> wrapPrimitive(Class<?> type) {
        if (type == null) return null;
        Class<?> wrapped = PRIMITIVE_WRAPPERS.get(type);
        return wrapped != null ? wrapped : type;
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        if (className == null || classLoader == null) return null;
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Class<?> cl = clazz;
        while (cl != null) {
            try {
                Method m = cl.getDeclaredMethod(methodName, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cl = cl.getSuperclass();
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "#" + methodName);
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        if (clazz == null || methodName == null) return null;
        try {
            return findMethodExact(clazz, methodName, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) throws Exception {
        if (obj == null) return null;
        Class<?> cl = obj.getClass();
        while (cl != null) {
            for (Method m : cl.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && isParametersCompatible(m.getParameterTypes(), args)) {
                    m.setAccessible(true);
                    return m.invoke(obj, args);
                }
            }
            cl = cl.getSuperclass();
        }
        throw new NoSuchMethodException(obj.getClass().getName() + "#" + methodName);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) throws Exception {
        if (clazz == null) return null;
        Class<?> cl = clazz;
        while (cl != null) {
            for (Method m : cl.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals(methodName) && isParametersCompatible(m.getParameterTypes(), args)) {
                    m.setAccessible(true);
                    return m.invoke(null, args);
                }
            }
            cl = cl.getSuperclass();
        }
        throw new NoSuchMethodException(clazz.getName() + "#" + methodName);
    }

    public static Object getFieldValue(Object obj, String fieldName) throws Exception {
        if (obj == null) return null;
        Class<?> cl = obj.getClass();
        while (cl != null) {
            try {
                Field f = cl.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                cl = cl.getSuperclass();
            }
        }
        throw new NoSuchFieldException(obj.getClass().getName() + "#" + fieldName);
    }

    public static boolean isParametersCompatible(Class<?>[] paramTypes, Object[] args) {
        int argsLen = args == null ? 0 : args.length;
        if (paramTypes.length != argsLen) return false;
        for (int i = 0; i < argsLen; i++) {
            Object arg = args[i];
            if (arg == null) {
                if (paramTypes[i].isPrimitive()) return false;
            } else {
                Class<?> wrapper = wrapPrimitive(paramTypes[i]);
                if (!wrapper.isAssignableFrom(arg.getClass())) return false;
            }
        }
        return true;
    }

    public static List<XposedInterface.HookHandle> hookAllMethods(XposedInterface xposed, Class<?> clazz, String methodName, XposedInterface.Hooker hooker) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        if (clazz == null || methodName == null) return handles;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                handles.add(xposed.hook(m).intercept(hooker));
            }
        }
        return handles;
    }

    public static List<XposedInterface.HookHandle> hookAllConstructors(XposedInterface xposed, Class<?> clazz, XposedInterface.Hooker hooker) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        if (clazz == null) return handles;
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            handles.add(xposed.hook(c).intercept(hooker));
        }
        return handles;
    }
}
