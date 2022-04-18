package com.tyron.builder.api.internal.provider;


public interface PropertyFactory {
    <T> DefaultProperty<T> property(Class<T> type);

    <T> DefaultListProperty<T> listProperty(Class<T> elementType);

    <T> DefaultSetProperty<T> setProperty(Class<T> elementType);

    <V, K> DefaultMapProperty<K, V> mapProperty(Class<K> keyType, Class<V> valueType);
}