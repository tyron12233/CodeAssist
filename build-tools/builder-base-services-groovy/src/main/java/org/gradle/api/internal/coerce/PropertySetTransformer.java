package org.gradle.api.internal.coerce;

public interface PropertySetTransformer {
    Object transformValue(Class<?> type, Object value);
}
