package com.melonhu.indexrecord.hook;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

final class BilibiliValues {
    private BilibiliValues() {}

    static Object get(Object object, String getter) {
        if (object == null) return null;
        try {
            Method method = object.getClass().getMethod(getter);
            method.setAccessible(true);
            return method.invoke(object);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static Object nondefault(Object object, String getter) {
        return Boolean.FALSE.equals(get(object, "has" + getter.substring(3))) ? null : get(object, getter);
    }

    static String text(Object object, String getter) {
        Object value = get(object, getter);
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    static Number number(Object object, String getter) {
        Object value = get(object, getter);
        return value instanceof Number ? (Number) value : null;
    }

    static long id(Object object, String getter) {
        Number value = number(object, getter);
        return value == null ? 0 : value.longValue();
    }

    static List<?> list(Object object, String getter) {
        Object value = get(object, getter + "List");
        if (value == null) value = get(object, getter);
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }
}
