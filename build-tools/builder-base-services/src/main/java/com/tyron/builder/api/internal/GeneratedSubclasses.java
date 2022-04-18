package com.tyron.builder.api.internal;

public class GeneratedSubclasses {

    private GeneratedSubclasses() {
    }

    public static Class<?> unpack(Class<?> type) {
        if (GeneratedSubclass.class.isAssignableFrom(type)) {
            try {
                return (Class<?>) type.getMethod("generatedFrom").invoke(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return type;
    }

    public static Class<?> unpackType(Object object) {
        if (object instanceof GeneratedSubclass) {
            return ((GeneratedSubclass) object).publicType();
        }
        return object.getClass();
    }
}