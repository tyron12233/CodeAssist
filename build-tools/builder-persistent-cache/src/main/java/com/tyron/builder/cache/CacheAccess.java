package com.tyron.builder.cache;


import com.tyron.builder.internal.Factory;

/**
 * Provides synchronised access to a cache.
 */
public interface CacheAccess {
    /**
     * Performs some work against the cache. Acquires exclusive locks on the appropriate resources, so that the given action is the only action to execute across all processes (including this one). Releases the locks and all resources at the end of the action.
     *
     * <p>This method is re-entrant, so that an action can call back into this method.</p>
     *
     * <p>Note: this method differs from {@link #withFileLock(Factory)} in that this method also blocks other threads from this process and all threads from other processes from accessing the cache.</p>
     */
    <T> T useCache(Factory<? extends T> action);

    /**
     * Performs some work against the cache. Acquires exclusive locks on the appropriate resources, so that the given action is the only action to execute across all processes (including this one). Releases the locks and all resources at the end of the action.
     *
     * <p>This method is re-entrant, so that an action can call back into this method.</p>
     *
     * <p>Note: this method differs from {@link #withFileLock(Runnable)} in that this method also blocks other threads from this process and all threads from other processes from accessing the cache.</p>
     */
    void useCache(Runnable action);

    /**
     * Performs some work against the cache. Acquires exclusive locks on the appropriate file resources, so that only the actions from this process may run. Releases the locks and all resources at the end of the action. Allows other threads from this process to execute, but does not allow any threads from any other process to run.
     *
     * <p>This method is re-entrant, so that an action can call back into this method.</p>
     */
    <T> T withFileLock(Factory<? extends T> action);

    /**
     * Performs some work against the cache. Acquires exclusive locks on the appropriate file resources, so that only the actions from this process may run. Releases the locks and all resources at the end of the action. Allows other threads from this process to execute, but does not allow any threads from any other process to run.
     *
     * <p>This method is re-entrant, so that an action can call back into this method.</p>
     */
    void withFileLock(Runnable action);

}