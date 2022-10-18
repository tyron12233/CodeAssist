package org.gradle.api.internal.rules;

import org.gradle.api.NamedDomainObjectFactory;

public interface NamedDomainObjectFactoryRegistry<T> {

    <U extends T> void registerFactory(Class<U> type, NamedDomainObjectFactory<? extends U> factory);
}
