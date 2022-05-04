package com.tyron.builder.api.internal.coerce;

public interface PropertySetTransformer {
    Object transformValue(Class<?> type, Object value);
}
