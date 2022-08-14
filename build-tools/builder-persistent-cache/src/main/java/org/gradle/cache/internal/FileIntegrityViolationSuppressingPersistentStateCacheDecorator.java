package org.gradle.cache.internal;

import org.gradle.cache.FileIntegrityViolationException;
import org.gradle.cache.PersistentStateCache;
import org.gradle.internal.Factory;

public class FileIntegrityViolationSuppressingPersistentStateCacheDecorator<T> implements PersistentStateCache<T> {

    private final PersistentStateCache<T> delegate;

    public FileIntegrityViolationSuppressingPersistentStateCacheDecorator(PersistentStateCache<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T get() {
        try {
            return delegate.get();
        } catch (FileIntegrityViolationException e) {
            return null;
        }
    }

    @Override
    public void set(T newValue) {
        delegate.set(newValue);
    }

    @Override
    public T update(final UpdateAction<T> updateAction) {
        return doUpdate(updateAction, new Factory<T>() {
            @Override
            public T create() {
                return delegate.update(updateAction);
            }
        });
    }

    @Override
    public T maybeUpdate(final UpdateAction<T> updateAction) {
        return doUpdate(updateAction, new Factory<T>() {
            @Override
            public T create() {
                return delegate.maybeUpdate(updateAction);
            }
        });
    }

    private T doUpdate(UpdateAction<T> updateAction, Factory<T> work) {
        try {
            return work.create();
        } catch (FileIntegrityViolationException e) {
            T newValue = updateAction.update(null);
            set(newValue);
            return newValue;
        }
    }
}
