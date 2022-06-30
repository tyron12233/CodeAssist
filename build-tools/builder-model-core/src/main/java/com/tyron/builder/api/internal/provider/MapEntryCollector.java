package com.tyron.builder.api.internal.provider;

import java.util.Map;

public interface MapEntryCollector<K, V> {

    void add(K key, V value, Map<K, V> dest);

    void addAll(Iterable<? extends Map.Entry<? extends K, ? extends V>> entries, Map<K, V> dest);
}