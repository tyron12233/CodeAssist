package com.tyron.builder.api.internal.rules;

import com.tyron.builder.api.NamedDomainObjectFactory;

public interface NamedDomainObjectFactoryRegistry<T> {

    <U extends T> void registerFactory(Class<U> type, NamedDomainObjectFactory<? extends U> factory);
}
