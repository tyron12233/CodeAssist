package com.tyron.builder.cache;

/**
 * A persistent store containing an object of type T.
 */
public interface PersistentStateCache<T> {
    /**
     * Fetches the value from this cache. A shared or exclusive lock is held while fetching the value, depending on implementation.
     */
    T get();

    /**
     * Sets the value for this cache. An exclusive lock is held while setting the value.
     */
    void set(T newValue);

    /**
     * Replaces the value for this cache.
     *
     * An exclusive lock is held while the update action is executing.
     * The result of the update is returned.
     */
    T update(UpdateAction<T> updateAction);

    interface UpdateAction<T> {
        T update(T oldValue);
    }

    /**
     * Potentially replaces the value for this cache
     *
     * The value returned by the update action will only be written to the cache
     * if it is not equal to the current value.
     *
     * An exclusive lock is held while the update action is executing.
     * The result of the update is returned, which may not be the object returned by the update action.
     */
    T maybeUpdate(UpdateAction<T> updateAction);

}