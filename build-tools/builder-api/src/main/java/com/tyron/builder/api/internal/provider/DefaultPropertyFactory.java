package com.tyron.builder.api.internal.provider;

public class DefaultPropertyFactory implements PropertyFactory {
    private final PropertyHost propertyHost;

    public DefaultPropertyFactory(PropertyHost propertyHost) {
        this.propertyHost = propertyHost;
    }

    @Override
    public <T> DefaultProperty<T> property(Class<T> type) {
        return new DefaultProperty<>(propertyHost, type);
    }

    @Override
    public <T> DefaultListProperty<T> listProperty(Class<T> elementType) {
        return new DefaultListProperty<>(propertyHost, elementType);
    }

    @Override
    public <T> DefaultSetProperty<T> setProperty(Class<T> elementType) {
        return new DefaultSetProperty<>(propertyHost, elementType);
    }

    @Override
    public <V, K> DefaultMapProperty<K, V> mapProperty(Class<K> keyType, Class<V> valueType) {
        return new DefaultMapProperty<>(propertyHost, keyType, valueType);
    }
}