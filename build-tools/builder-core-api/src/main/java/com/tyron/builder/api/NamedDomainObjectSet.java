package com.tyron.builder.api;

import groovy.lang.Closure;

import java.util.Set;
import java.util.function.Predicate;

/**
 * <p>A specialization of {@link NamedDomainObjectCollection} that also implements {@link Set} and orders objects by their inherent name.</p>
 *
 * <p>All object equality is determined in terms of object names. That is, calling {@code remove()} with an object that is NOT equal to
 * an existing object in terms of {@code equals}, but IS in terms of name equality will result in the existing collection item with
 * the equal name being removed.</p>
 *
 * <p>You can create an instance of this type using the factory method {@link ObjectFactory#namedDomainObjectSet(Class)}.</p>
 *
 * @param <T> The type of objects in the set
 */
public interface NamedDomainObjectSet<T> extends NamedDomainObjectCollection<T>, Set<T> {

    /**
     * {@inheritDoc}
     */
    @Override
    <S extends T> NamedDomainObjectSet<S> withType(Class<S> type);

    /**
     * {@inheritDoc}
     */
    @Override
    NamedDomainObjectSet<T> matching(Predicate<? super T> spec);

    /**
     * {@inheritDoc}
     */
    @Override
    NamedDomainObjectSet<T> matching(Closure spec);

    /**
     * {@inheritDoc}
     */
    @Override
    Set<T> findAll(Closure spec);
}
