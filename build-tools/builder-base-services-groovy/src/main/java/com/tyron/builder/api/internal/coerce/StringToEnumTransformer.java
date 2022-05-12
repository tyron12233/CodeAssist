package com.tyron.builder.api.internal.coerce;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.util.GUtil;

import org.codehaus.groovy.reflection.CachedClass;

public class StringToEnumTransformer implements MethodArgumentsTransformer, PropertySetTransformer {

    public static final StringToEnumTransformer INSTANCE = new StringToEnumTransformer();

    @Override
    public Object[] transform(CachedClass[] types, Object[] args) {
        boolean needsTransform = false;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            Class<?> type = types[i].getTheClass();
            if (type.isInstance(arg) || arg == null) {
                // Can use arg without conversion
                continue;
            }
            if (!(arg instanceof CharSequence && type.isEnum())) {
                // Cannot convert
                return args;
            }
            needsTransform = true;
        }
        if (!needsTransform) {
            return args;
        }
        Object[] transformed = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            Class<?> type = types[i].getTheClass();
            if (type.isEnum() && arg instanceof CharSequence) {
                transformed[i] = toEnumValue(Cast.uncheckedNonnullCast(type), arg);
            } else {
                transformed[i] = args[i];
            }
        }
        return transformed;
    }

    @Override
    public boolean canTransform(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof CharSequence) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object transformValue(Class<?> type, Object value) {
        if (value instanceof CharSequence && type.isEnum()) {
            @SuppressWarnings("unchecked") Class<? extends Enum<?>> enumType = (Class<? extends Enum<?>>) type;
            return toEnumValue(Cast.uncheckedNonnullCast(enumType), value);
        }

        return value;
    }

    static public <T extends Enum<T>> T toEnumValue(Class<T> enumType, Object value) {
        return GUtil.toEnum(enumType, value);
    }
}
