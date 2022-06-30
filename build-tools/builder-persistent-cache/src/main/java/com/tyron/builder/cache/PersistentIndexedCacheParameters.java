package com.tyron.builder.cache;

import com.tyron.builder.internal.serialize.BaseSerializerFactory;
import com.tyron.builder.internal.serialize.Serializer;

import org.jetbrains.annotations.Nullable;

public class PersistentIndexedCacheParameters<K, V> {
    private static final BaseSerializerFactory SERIALIZER_FACTORY = new BaseSerializerFactory();

    private final String cacheName;
    private final Serializer<K> keySerializer;
    private final Serializer<V> valueSerializer;
    private final CacheDecorator cacheDecorator;

    public static <K, V> PersistentIndexedCacheParameters<K, V> of(String cacheName, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        return new PersistentIndexedCacheParameters<K, V>(cacheName, keySerializer, valueSerializer, null);
    }

    public static <K, V> PersistentIndexedCacheParameters<K, V> of(String cacheName, Class<K> keyType, Serializer<V> valueSerializer) {
        return new PersistentIndexedCacheParameters<K, V>(cacheName, SERIALIZER_FACTORY.getSerializerFor(keyType), valueSerializer, null);
    }

    public static <K, V> PersistentIndexedCacheParameters<K, V> of(String cacheName, Class<K> keyType, Class<V> valueType) {
        return new PersistentIndexedCacheParameters<K, V>(cacheName, SERIALIZER_FACTORY.getSerializerFor(keyType), SERIALIZER_FACTORY.getSerializerFor(valueType), null);
    }

    private PersistentIndexedCacheParameters(String cacheName, Serializer<K> keySerializer, Serializer<V> valueSerializer, @Nullable CacheDecorator cacheDecorator) {
        this.cacheName = cacheName;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        this.cacheDecorator = cacheDecorator;
    }

    public String getCacheName() {
        return cacheName;
    }

    public Serializer<K> getKeySerializer() {
        return keySerializer;
    }

    public Serializer<V> getValueSerializer() {
        return valueSerializer;
    }

    @Nullable
    public CacheDecorator getCacheDecorator() {
        return cacheDecorator;
    }

    public PersistentIndexedCacheParameters<K, V> withCacheDecorator(CacheDecorator cacheDecorator) {
        return new PersistentIndexedCacheParameters<K, V>(cacheName, keySerializer, valueSerializer, cacheDecorator);
    }
}