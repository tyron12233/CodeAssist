package org.gradle.internal.instantiation.generator.android;

import java.lang.reflect.Field;

import sun.misc.Unsafe;

public class UnsafeUtil {

    private static final Unsafe unsafe;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Unsafe getUnsafe() {
        return unsafe;
    }
}
