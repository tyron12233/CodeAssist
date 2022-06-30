package com.tyron.builder.internal.serialize;

import com.google.common.base.Objects;

/**
 * This abstract class provide a sensible default implementation for {@code Serializer} equality. This equality
 * implementation is required to enable cache instance reuse within the same Gradle runtime. Serializers are used
 * as cache parameter which need to be compared to determine compatible cache.
 */
public abstract class AbstractSerializer<T> implements Serializer<T> {
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        return Objects.equal(obj.getClass(), getClass());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}