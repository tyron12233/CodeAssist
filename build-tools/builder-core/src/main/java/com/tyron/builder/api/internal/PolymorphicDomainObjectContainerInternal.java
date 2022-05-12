package com.tyron.builder.api.internal;

import com.tyron.builder.api.PolymorphicDomainObjectContainer;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;

import java.util.Set;

public interface PolymorphicDomainObjectContainerInternal<T> extends PolymorphicDomainObjectContainer<T> {

    Set<? extends Class<? extends T>> getCreateableTypes();

    NamedEntityInstantiator<T> getEntityInstantiator();
}
