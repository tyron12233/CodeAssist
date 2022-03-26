package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.UncheckedException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

/**
 * A provider whose value is computed by a {@link Callable}.
 *
 * <h3>Configuration Cache Behavior</h3>
 * <b>Eager</b>. The value is computed at store time and loaded from the cache.
 */
public class DefaultProvider<T> extends AbstractMinimalProvider<T> {
    private final Callable<? extends T> value;

    public DefaultProvider(Callable<? extends T> value) {
        this.value = value;
    }

    @Nullable
    @Override
    public Class<T> getType() {
        // guard against https://youtrack.jetbrains.com/issue/KT-36297
        try {
            return inferTypeFromCallableGenericArgument();
        } catch (NoClassDefFoundError e) {
            return null;
        }
    }

    @Nullable
    private Class<T> inferTypeFromCallableGenericArgument() {
        // We could do a better job of figuring this out
        // Extract the type for common case that is quick to calculate
        for (Type superType : value.getClass().getGenericInterfaces()) {
            if (superType instanceof ParameterizedType) {
                ParameterizedType parameterizedSuperType = (ParameterizedType) superType;
                if (parameterizedSuperType.getRawType().equals(Callable.class)) {
                    Type argument = parameterizedSuperType.getActualTypeArguments()[0];
                    if (argument instanceof Class) {
                        return Cast.uncheckedCast(argument);
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected Value<? extends T> calculateOwnValue(ValueConsumer consumer) {
        try {
            return Value.ofNullable(value.call());
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }
}