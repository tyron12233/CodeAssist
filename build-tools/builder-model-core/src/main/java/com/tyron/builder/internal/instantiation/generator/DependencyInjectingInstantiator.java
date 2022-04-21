package com.tyron.builder.internal.instantiation.generator;

import com.tyron.builder.api.Describable;
import com.tyron.builder.internal.instantiation.InstanceFactory;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.instantiation.generator.ConstructorSelector;
import com.tyron.builder.internal.reflect.service.ServiceLookup;
import com.tyron.builder.api.reflect.ObjectInstantiationException;

public class DependencyInjectingInstantiator implements InstanceGenerator {
    public DependencyInjectingInstantiator(ConstructorSelector constructorSelector,
                                           ServiceLookup defaultServices) {

    }

    @Override
    public <T> T newInstanceWithDisplayName(Class<? extends T> type,
                                            Describable displayName,
                                            Object... parameters) throws ObjectInstantiationException {
        return null;
    }

    @Override
    public <T> T newInstance(Class<? extends T> type,
                             Object... parameters) throws ObjectInstantiationException {
        return null;
    }

    public <T> InstanceFactory<T> factoryFor(Class<T> type) {
        throw new UnsupportedOperationException("");
    }
}
