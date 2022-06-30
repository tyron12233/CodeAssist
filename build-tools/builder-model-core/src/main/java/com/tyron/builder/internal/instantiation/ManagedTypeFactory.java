package com.tyron.builder.internal.instantiation;

import com.google.common.base.Objects;
import com.tyron.builder.api.reflect.ObjectInstantiationException;
import com.tyron.builder.internal.state.ManagedFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class ManagedTypeFactory implements ManagedFactory {
    private final Constructor<?> constructor;

    public ManagedTypeFactory(Class<?> type) {
        try {
            constructor = type.getConstructor(Object[].class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("The class " + type.getSimpleName() + " does not appear to a be a generated managed class.", e);
        }
    }

    @Override
    public <T> T fromState(Class<T> type, Object state) {
        if (!type.isAssignableFrom(constructor.getDeclaringClass())) {
            return null;
        }
        try {
            return type.cast(constructor.newInstance(state));
        } catch (InvocationTargetException e) {
            throw new ObjectInstantiationException(type, e.getCause());
        } catch (Exception e) {
            throw new ObjectInstantiationException(type, e);
        }
    }

    @Override
    public int getId() {
        return Objects.hashCode(constructor.getDeclaringClass().getName());
    }
}
