package org.gradle.api.internal;

import org.gradle.api.internal.rules.NamedDomainObjectFactoryRegistry;
import org.gradle.model.internal.core.NamedEntityInstantiator;

import java.util.Set;

public interface PolymorphicNamedEntityInstantiator<T> extends NamedEntityInstantiator<T>, NamedDomainObjectFactoryRegistry<T> {
    Set<? extends Class<? extends T>> getCreatableTypes();
}
