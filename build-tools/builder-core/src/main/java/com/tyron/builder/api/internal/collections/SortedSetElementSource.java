package com.tyron.builder.api.internal.collections;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.DefaultMutationGuard;
import com.tyron.builder.api.internal.MutationGuard;
import com.tyron.builder.api.internal.provider.ChangingValue;
import com.tyron.builder.api.internal.provider.CollectionProviderInternal;
import com.tyron.builder.api.internal.provider.ProviderInternal;
import com.tyron.builder.internal.Cast;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.TreeSet;

public class SortedSetElementSource<T> implements ElementSource<T> {
    private final TreeSet<T> values;
    private final PendingSource<T> pending = new DefaultPendingSource<T>();
    private final MutationGuard mutationGuard = new DefaultMutationGuard();

    public SortedSetElementSource(Comparator<T> comparator) {
        this.values = new TreeSet<T>(comparator);
    }

    @Override
    public boolean isEmpty() {
        return values.isEmpty() && pending.isEmpty();
    }

    @Override
    public boolean constantTimeIsEmpty() {
        return values.isEmpty() && pending.isEmpty();
    }

    @Override
    public int size() {
        return values.size() + pending.size();
    }

    @Override
    public int estimatedSize() {
        return values.size() + pending.size();
    }

    @Override
    public Iterator<T> iterator() {
        pending.realizePending();
        return values.iterator();
    }

    @Override
    public Iterator<T> iteratorNoFlush() {
        return values.iterator();
    }

    @Override
    public boolean contains(Object element) {
        pending.realizePending();
        return values.contains(element);
    }

    @Override
    public boolean containsAll(Collection<?> elements) {
        pending.realizePending();
        return values.containsAll(elements);
    }

    @Override
    public boolean add(T element) {
        return values.add(element);
    }

    @Override
    public boolean addRealized(T element) {
        return values.add(element);
    }

    @Override
    public boolean remove(Object o) {
        return values.remove(o);
    }

    @Override
    public void clear() {
        pending.clear();
        values.clear();
    }

    @Override
    public void realizePending() {
        pending.realizePending();
    }

    @Override
    public void realizePending(Class<?> type) {
        pending.realizePending(type);
    }

    @Override
    public boolean addPending(final ProviderInternal<? extends T> provider) {
        if (provider instanceof ChangingValue) {
            Cast.<ChangingValue<T>>uncheckedNonnullCast(provider).onValueChange(previousValue -> {
                values.remove(previousValue);
                pending.addPending(provider);
            });
        }
        return pending.addPending(provider);
    }

    @Override
    public boolean removePending(ProviderInternal<? extends T> provider) {
        return pending.removePending(provider);
    }

    @Override
    public boolean addPendingCollection(final CollectionProviderInternal<T, ? extends Iterable<T>> provider) {
        if (provider instanceof ChangingValue) {
            Cast.<ChangingValue<Iterable<T>>>uncheckedNonnullCast(provider).onValueChange(previousValues -> {
                for (T value : previousValues) {
                    values.remove(value);
                }
                pending.addPendingCollection(provider);
            });
        }
        return pending.addPendingCollection(provider);
    }

    @Override
    public boolean removePendingCollection(CollectionProviderInternal<T, ? extends Iterable<T>> provider) {
        return pending.removePendingCollection(provider);
    }

    @Override
    public void onRealize(Action<T> action) {
        pending.onRealize(action);
    }

    @Override
    public void realizeExternal(ProviderInternal<? extends T> provider) {
        pending.realizeExternal(provider);
    }

    @Override
    public MutationGuard getMutationGuard() {
        return mutationGuard;
    }
}
