package com.tyron.builder.api.internal.tasks.options;

import java.lang.reflect.Type;

public interface PropertySetter {
    Class<?> getDeclaringClass();

    Class<?> getRawType();

    Type getGenericType();

    void setValue(Object object, Object value);
}
