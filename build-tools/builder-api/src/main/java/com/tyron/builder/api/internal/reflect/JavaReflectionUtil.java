package com.tyron.builder.api.internal.reflect;

import com.tyron.builder.api.internal.UncheckedException;

import java.lang.reflect.Constructor;

public class JavaReflectionUtil {

    public static Class<?> getWrapperTypeForPrimitiveType(Class<?> type) {
        if (type == Character.TYPE) {
            return Character.class;
        } else if (type == Boolean.TYPE) {
            return Boolean.class;
        } else if (type == Long.TYPE) {
            return Long.class;
        } else if (type == Integer.TYPE) {
            return Integer.class;
        } else if (type == Short.TYPE) {
            return Short.class;
        } else if (type == Byte.TYPE) {
            return Byte.class;
        } else if (type == Float.TYPE) {
            return Float.class;
        } else if (type == Double.TYPE) {
            return Double.class;
        }
        throw new IllegalArgumentException(String.format("Don't know the wrapper type for primitive type %s.", type));
    }

    /**
     * This is intended to be a equivalent of deprecated {@link Class#newInstance()}.
     */
    public static <T> T newInstance(Class<T> c) {
        try {
            Constructor<T> constructor = c.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Throwable e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }
}

