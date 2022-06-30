package com.tyron.builder.api;

import java.util.Set;
import java.util.function.Predicate;

import groovy.lang.Closure;

/**
 * <p>A {@code DomainObjectSet} is a specialization of {@link DomainObjectCollection} that guarantees {@link Set} semantics.</p>
 *
 * <p>You can create an instance of this type using the factory method {@link org.gradle.api.model.ObjectFactory#domainObjectSet(Class)}.</p>
 *
 * @param <T> The type of objects in this set.
 */
public interface DomainObjectSet<T> extends DomainObjectCollection<T>, Set<T> {

    /**
     * {@inheritDoc}
     */
    @Override
    <S extends T> DomainObjectSet<S> withType(Class<S> type);

    /**
     * {@inheritDoc}
     */
    @Override
    DomainObjectSet<T> matching(Predicate<? super T> spec);

    /**
     * {@inheritDoc}
     */
    @Override
    DomainObjectSet<T> matching(Closure spec);

    /**
     * {@inheritDoc}
     */
    @Override
    Set<T> findAll(Closure spec);
}
