package org.gradle.api.internal;

import org.gradle.api.PolymorphicDomainObjectContainer;
import org.gradle.model.internal.core.NamedEntityInstantiator;

import java.util.Set;

public interface PolymorphicDomainObjectContainerInternal<T> extends PolymorphicDomainObjectContainer<T> {

    Set<? extends Class<? extends T>> getCreateableTypes();

    NamedEntityInstantiator<T> getEntityInstantiator();
}
