package com.tyron.builder.api.internal.reflect;

import java.util.WeakHashMap;

/**
 * A generic purpose, thread-safe cache which is aimed at storing information
 * about a class. The cache is a hierarchical cache, which key is a composite
 * of a receiver, and argument types. All those, key or arguments, are kept
 * in a weak reference, allowing the GC to recover memory if required.
 *
 * @param <T> the type of the element stored in the cache.
 */
public abstract class ReflectionCache<T extends CachedInvokable<?>> {
    private final Object lock = new Object();

    private final WeaklyClassReferencingCache cache = new WeaklyClassReferencingCache();

    public T get(final Class<?> receiver, final Class<?>[] key) {
        synchronized (lock) {
            return cache.get(receiver, key);
        }
    }

    protected abstract T create(Class<?> receiver, Class<?>[] key);

    public int size() {
        return cache.size();
    }

    private class WeaklyClassReferencingCache extends WeakHashMap<Class<?>, CacheEntry> {

        public T get(Class<?> receiver, Class<?>[] classes) {
            WeaklyClassReferencingCache cur = this;
            CacheEntry last = fetchNext(cur, receiver);
            for (Class<?> aClass : classes) {
                cur = last.table;
                last = fetchNext(cur, aClass);
            }
            if (last.value == null || last.value.getMethod() == null) {
                last.value = create(receiver, classes);
            }
            return last.value;
        }

        private CacheEntry fetchNext(WeaklyClassReferencingCache cur, Class<?> aClass) {
            CacheEntry last;
            last = cur.get(aClass);
            if (last == null) {
                last = new CacheEntry();
                cur.put(aClass, last);
            }
            return last;
        }
    }

    private class CacheEntry {
        private WeaklyClassReferencingCache table = new WeaklyClassReferencingCache();
        private T value;
    }

}
