package com.tyron.builder.api.internal;

import com.tyron.builder.api.NamedDomainObjectFactory;
import com.tyron.builder.internal.reflect.Instantiator;

public class ReflectiveNamedDomainObjectFactory<T> implements NamedDomainObjectFactory<T> {
    private final Class<? extends T> type;
    private final Object[] extraArgs;
    private final Instantiator instantiator;

    public ReflectiveNamedDomainObjectFactory(Class<? extends T> type, Instantiator instantiator, Object... extraArgs) {
        this.type = type;
        this.instantiator = instantiator;
        this.extraArgs = extraArgs;
    }

    @Override
    public T create(String name) {
        return instantiator.newInstance(type, combineInstantiationArgs(name));
    }

    protected Object[] combineInstantiationArgs(String name) {
        Object[] combinedArgs;
        if (extraArgs.length == 0) {
            Object[] nameArg = {name};
            combinedArgs = nameArg;
        } else {
            combinedArgs = new Object[extraArgs.length + 1];
            combinedArgs[0] = name;
            int i = 1;
            for (Object e : extraArgs) {
                combinedArgs[i++] = e;
            }
        }

        return combinedArgs;
    }
}
