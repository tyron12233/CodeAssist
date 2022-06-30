package com.tyron.builder.api.internal;

import com.tyron.builder.api.internal.rules.NamedDomainObjectFactoryRegistry;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;

import java.util.Set;

public interface PolymorphicNamedEntityInstantiator<T> extends NamedEntityInstantiator<T>, NamedDomainObjectFactoryRegistry<T> {
    Set<? extends Class<? extends T>> getCreatableTypes();
}
