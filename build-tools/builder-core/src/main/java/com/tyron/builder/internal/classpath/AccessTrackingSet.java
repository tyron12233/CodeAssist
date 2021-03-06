package com.tyron.builder.internal.classpath;

import com.google.common.collect.ForwardingSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The special-cased implementation of {@link Set} that tracks all accesses to its elements.
 * @param <E> the type of elements
 */
class AccessTrackingSet<E> extends ForwardingSet<E> {
    // TODO(https://github.com/gradle/configuration-cache/issues/337) Only a limited subset of entrySet/keySet methods are currently tracked.
    private final Set<? extends E> delegate;
    private final Consumer<Object> onAccess;
    private final Runnable onAggregatingAccess;

    public AccessTrackingSet(Set<? extends E> delegate, Consumer<Object> onAccess, Runnable onAggregatingAccess) {
        this.delegate = delegate;
        this.onAccess = onAccess;
        this.onAggregatingAccess = onAggregatingAccess;
    }

    @Override
    public boolean contains(@Nullable Object o) {
        boolean result = delegate.contains(o);
        onAccess.accept(o);
        return result;
    }

    @Override
    public boolean containsAll(@Nonnull Collection<?> collection) {
        boolean result = delegate.containsAll(collection);
        for (Object o : collection) {
            onAccess.accept(o);
        }
        return result;
    }

    @Override
    public boolean remove(Object o) {
        // We cannot perform modification before notifying because the listener may want to query the state of the delegate prior to that.
        onAccess.accept(o);
        return delegate.remove(o);
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        // We cannot perform modification before notifying because the listener may want to query the state of the delegate prior to that.
        for (Object o : collection) {
            onAccess.accept(o);
        }
        return delegate.removeAll(collection);
    }

    @Override
    public Iterator<E> iterator() {
        reportAggregatingAccess();
        return delegate().iterator();
    }

    @Override
    public int size() {
        reportAggregatingAccess();
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        reportAggregatingAccess();
        return delegate.isEmpty();
    }

    @Override
    public boolean equals(@Nullable Object object) {
        reportAggregatingAccess();
        return super.equals(object);
    }

    @Override
    public int hashCode() {
        reportAggregatingAccess();
        return super.hashCode();
    }

    @Override
    public Object[] toArray() {
        reportAggregatingAccess();
        return delegate.toArray();
    }

    @Override
    public <T> T[] toArray(T[] array) {
        reportAggregatingAccess();
        return delegate.toArray(array);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Set<E> delegate() {
        // The entrySet/keySet disallow adding elements to the set, making it covariant, so downcast is safe there.
        return (Set<E>) delegate;
    }

    private void reportAggregatingAccess() {
        onAggregatingAccess.run();
    }
}
