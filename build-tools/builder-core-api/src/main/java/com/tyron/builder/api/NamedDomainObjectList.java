package com.tyron.builder.api;

import groovy.lang.Closure;

import java.util.List;
import java.util.function.Predicate;

/**
 * <p>A specialization of {@link NamedDomainObjectCollection} that also implements {@link java.util.List}.</p>
 *
 * <p>All object equality is determined in terms of object names. That is, calling {@code remove()} with an object that is NOT equal to
 * an existing object in terms of {@code equals}, but IS in terms of name equality will result in the existing collection item with
 * the equal name being removed.</p>
 *
 * <p>You can create an instance of this type using the factory method {@link ObjectFactory#namedDomainObjectList(Class)}.</p>
 *
 * @param <T> The type of objects in the list
 */
public interface NamedDomainObjectList<T> extends NamedDomainObjectCollection<T>, List<T> {
    /**
     * {@inheritDoc}
     */
    @Override
    <S extends T> NamedDomainObjectList<S> withType(Class<S> type);

    /**
     * {@inheritDoc}
     */
    @Override
    NamedDomainObjectList<T> matching(Predicate<? super T> spec);

    /**
     * {@inheritDoc}
     */
    @Override
    NamedDomainObjectList<T> matching(Closure spec);

    /**
     * {@inheritDoc}
     */
    @Override
    List<T> findAll(Closure spec);
}
