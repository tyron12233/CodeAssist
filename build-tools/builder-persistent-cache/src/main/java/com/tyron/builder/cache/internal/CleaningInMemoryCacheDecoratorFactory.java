package com.tyron.builder.cache.internal;

import com.google.common.collect.Lists;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

public class CleaningInMemoryCacheDecoratorFactory extends DefaultInMemoryCacheDecoratorFactory {
    private final List<WeakReference<InMemoryCacheController>> inMemoryCaches = Lists.newArrayList();

    public CleaningInMemoryCacheDecoratorFactory(boolean longLivingProcess, CrossBuildInMemoryCacheFactory cacheFactory) {
        super(longLivingProcess, cacheFactory);
    }

    public void clearCaches(Predicate<InMemoryCacheController> predicate) {
        synchronized (inMemoryCaches) {
            for (Iterator<WeakReference<InMemoryCacheController>> iterator = inMemoryCaches.iterator(); iterator.hasNext();) {
                WeakReference<InMemoryCacheController> ref = iterator.next();
                InMemoryCacheController cache = ref.get();
                if (cache == null) {
                    iterator.remove();
                } else if (predicate.test(cache)) {
                    cache.clearInMemoryCache();
                }
            }
        }
    }

    @Override
    protected <K, V> MultiProcessSafeAsyncPersistentIndexedCache<K, V> applyInMemoryCaching(String cacheId, MultiProcessSafeAsyncPersistentIndexedCache<K, V> backingCache, int maxEntriesToKeepInMemory, boolean cacheInMemoryForShortLivedProcesses) {
        MultiProcessSafeAsyncPersistentIndexedCache<K, V> delegate = super.applyInMemoryCaching(cacheId, backingCache, maxEntriesToKeepInMemory, cacheInMemoryForShortLivedProcesses);
        if (delegate instanceof InMemoryCacheController) {
            InMemoryCacheController cimc = (InMemoryCacheController) delegate;
            WeakReference<InMemoryCacheController> ref = new WeakReference<>(cimc);
            synchronized (inMemoryCaches) {
                inMemoryCaches.add(ref);
            }
        }
        return delegate;
    }
}
