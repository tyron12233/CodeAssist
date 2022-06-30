package com.tyron.builder.internal.execution.history.impl;

import static com.google.common.collect.ImmutableSortedMap.copyOfSorted;
import static com.google.common.collect.Maps.transformValues;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Interner;
import com.tyron.builder.cache.CacheDecorator;
import com.tyron.builder.cache.PersistentCache;
import com.tyron.builder.cache.PersistentIndexedCache;
import com.tyron.builder.cache.PersistentIndexedCacheParameters;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.internal.execution.history.AfterExecutionState;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;

import java.util.Optional;
import java.util.function.Supplier;

public class DefaultExecutionHistoryStore implements ExecutionHistoryStore {

    private final PersistentIndexedCache<String, PreviousExecutionState> store;

    public DefaultExecutionHistoryStore(Supplier<PersistentCache> cache,
                                        InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory,
                                        Interner<String> stringInterner) {
        DefaultPreviousExecutionStateSerializer serializer =
                new DefaultPreviousExecutionStateSerializer(
                        new FileCollectionFingerprintSerializer(stringInterner),
                        new FileSystemSnapshotSerializer(stringInterner));

        CacheDecorator inMemoryCacheDecorator =
                inMemoryCacheDecoratorFactory.decorator(10000, false);
        this.store = cache.get().createCache(
                PersistentIndexedCacheParameters.of("executionHistory", String.class, serializer)
                        .withCacheDecorator(inMemoryCacheDecorator));
    }

    @Override
    public Optional<PreviousExecutionState> load(String key) {
        return Optional.ofNullable(store.getIfPresent(key));
    }

    @Override
    public void store(String key, boolean successful, AfterExecutionState executionState) {
        store.put(key, new DefaultPreviousExecutionState(executionState.getOriginMetadata(),
                executionState.getImplementation(), executionState.getAdditionalImplementations(),
                executionState.getInputProperties(),
                prepareForSerialization(executionState.getInputFileProperties()),
                executionState.getOutputFilesProducedByWork(), successful));
    }

    @Override
    public void remove(String key) {
        store.remove(key);
    }

    private static ImmutableSortedMap<String, FileCollectionFingerprint> prepareForSerialization(
            ImmutableSortedMap<String, CurrentFileCollectionFingerprint> fingerprints) {
        return copyOfSorted(transformValues(fingerprints,
                value -> value.archive(SerializableFileCollectionFingerprint::new)));
    }
}
