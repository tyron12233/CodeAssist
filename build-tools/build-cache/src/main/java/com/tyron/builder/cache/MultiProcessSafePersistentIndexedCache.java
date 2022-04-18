package com.tyron.builder.cache;

/**
 * A {@link PersistentIndexedCache} implementation that is aware of file locking.
 */
public interface MultiProcessSafePersistentIndexedCache<K, V> extends PersistentIndexedCache<K, V>, UnitOfWorkParticipant {
}