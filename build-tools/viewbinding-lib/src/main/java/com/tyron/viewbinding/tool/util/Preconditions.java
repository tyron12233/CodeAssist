package com.tyron.viewbinding.tool.util;

/**
 * Simple Preconditions utility class, similar to guava's but reports errors via L
 */
public class Preconditions {
    public static void check(boolean value, String error, Object... args) {
        if (!value) {
            L.e(error, args);
        }
    }

    public static void checkNotNull(Object value, String error, Object... args) {
        if (value == null) {
            L.e(error, args);
        }
    }

    public static void checkNull(Object value, String error, Object... args) {
        if (value != null) {
            L.e(error, args);
        }
    }
}
