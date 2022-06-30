package com.tyron.builder.api.internal.provider;

import java.util.Map;

public interface MapProviderInternal<K, V> extends ProviderInternal<Map<K, V>> {

    Class<? extends K> getKeyType();

    Class<? extends V> getValueType();
}