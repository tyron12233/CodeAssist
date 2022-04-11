package com.tyron.builder.api.internal.provider;

import com.google.common.base.Preconditions;

import java.util.Map;

class ValidatingMapEntryCollector<K, V> implements MapEntryCollector<K, V> {

    private final Class<K> keyType;
    private final Class<V> valueType;
    private final ValueSanitizer<K> keySanitizer;
    private final ValueSanitizer<V> valueSanitizer;

    public ValidatingMapEntryCollector(Class<K> keyType, Class<V> valueType, ValueSanitizer<K> keySanitizer, ValueSanitizer<V> valueSanitizer) {
        this.keyType = keyType;
        this.valueType = valueType;
        this.keySanitizer = keySanitizer;
        this.valueSanitizer = valueSanitizer;
    }

    @Override
    public void add(K key, V value, Map<K, V> dest) {
        Preconditions.checkNotNull(
                key,
                "Cannot get the value of a property of type %s with key type %s as the source contains a null key.",
                Map.class.getName(), keyType.getName());
        Preconditions.checkNotNull(
                value,
                "Cannot get the value of a property of type %s with value type %s as the source contains a null value for key \"%s\".",
                Map.class.getName(), valueType.getName(), key);

        K sanitizedKey = keySanitizer.sanitize(key);
        if (!keyType.isInstance(sanitizedKey)) {
            throw new IllegalArgumentException(String.format(
                    "Cannot get the value of a property of type %s with key type %s as the source contains a key of type %s.",
                    Map.class.getName(), keyType.getName(), key.getClass().getName()));
        }

        V sanitizedValue = valueSanitizer.sanitize(value);
        if (!valueType.isInstance(sanitizedValue)) {
            throw new IllegalArgumentException(String.format(
                    "Cannot get the value of a property of type %s with value type %s as the source contains a value of type %s.",
                    Map.class.getName(), valueType.getName(), value.getClass().getName()));
        }

        dest.put(sanitizedKey, sanitizedValue);
    }

    @Override
    public void addAll(Iterable<? extends Map.Entry<? extends K, ? extends V>> entries, Map<K, V> dest) {
        for (Map.Entry<? extends K, ? extends V> entry : entries) {
            add(entry.getKey(), entry.getValue(), dest);
        }
    }
}