package com.tyron.builder.api.internal.collections;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.provider.CollectionProviderInternal;
import com.tyron.builder.api.internal.provider.Collectors.ElementFromProvider;
import com.tyron.builder.api.internal.provider.Collectors.ElementsFromCollectionProvider;
import com.tyron.builder.api.internal.provider.Collectors.TypedCollector;
import com.tyron.builder.api.internal.provider.ProviderInternal;

import java.util.Iterator;
import java.util.List;

public class DefaultPendingSource<T> implements PendingSource<T> {
    private final List<TypedCollector<T>> pending = Lists.newArrayList();
    private Action<T> flushAction;

    @Override
    public void realizePending() {
        if (!pending.isEmpty()) {
            List<TypedCollector<T>> copied = Lists.newArrayList(pending);
            realize(copied);
        }
    }

    @Override
    public void realizePending(Class<?> type) {
        if (!pending.isEmpty()) {
            List<TypedCollector<T>> copied = Lists.newArrayList();
            for (TypedCollector<T> collector : pending) {
                if (collector.getType() == null || type.isAssignableFrom(collector.getType())) {
                    copied.add(collector);
                }
            }
            realize(copied);
        }
    }

    private void realize(Iterable<TypedCollector<T>> collectors) {
        for (TypedCollector<T> collector : collectors) {
            if (flushAction != null) {
                pending.remove(collector);
                ImmutableList.Builder<T> builder = ImmutableList.builder();
                collector.collectInto(builder);
                List<T> realized = builder.build();
                for (T element : realized) {
                    flushAction.execute(element);
                }
            } else {
                throw new IllegalStateException("Cannot realize pending elements when realize action is not set");
            }
        }
    }

    @Override
    public boolean addPending(ProviderInternal<? extends T> provider) {
        return pending.add(new TypedCollector<T>(provider.getType(), new ElementFromProvider<T>(provider)));
    }

    @Override
    public boolean removePending(ProviderInternal<? extends T> provider) {
        return removeByProvider(provider);
    }

    private boolean removeByProvider(ProviderInternal<?> provider) {
        Iterator<TypedCollector<T>> iterator = pending.iterator();
        while (iterator.hasNext()) {
            TypedCollector<T> collector = iterator.next();
            if (collector.isProvidedBy(provider)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean addPendingCollection(CollectionProviderInternal<T, ? extends Iterable<T>> provider) {
        return pending.add(new TypedCollector<T>(provider.getElementType(), new ElementsFromCollectionProvider<T>(provider)));
    }

    @Override
    public boolean removePendingCollection(CollectionProviderInternal<T, ? extends Iterable<T>> provider) {
        return removeByProvider(provider);
    }

    @Override
    public void onRealize(Action<T> action) {
        this.flushAction = action;
    }

    @Override
    public void realizeExternal(ProviderInternal<? extends T> provider) {
        removePending(provider);
    }

    @Override
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    @Override
    public int size() {
        int count = 0;
        for (TypedCollector<T> collector : pending) {
            count += collector.size();
        }
        return count;
    }

    @Override
    public void clear() {
        pending.clear();
    }
}
