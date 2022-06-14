package com.tyron.builder.api.java.archives.internal;

import com.tyron.builder.api.java.archives.Attributes;
import com.tyron.builder.api.java.archives.ManifestException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class DefaultAttributes implements Attributes {
    protected Map<String, Object> attributes = new LinkedHashMap<String, Object>();

    @Override
    public int size() {
        return attributes.size();
    }

    @Override
    public boolean isEmpty() {
        return attributes.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return attributes.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return attributes.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return attributes.get(key);
    }

    @Override
    public Object put(String key, Object value) {
        if (key == null) {
            throw new ManifestException("The key of a manifest attribute must not be null.");
        }
        if (value == null) {
            throw new ManifestException(String.format("The value of a manifest attribute must not be null (Key=%s).", key));
        }
        try {
            new java.util.jar.Attributes.Name(key);
        } catch (IllegalArgumentException e) {
            throw new ManifestException(String.format("The Key=%s violates the Manifest spec!", key));
        }
        return attributes.put(key, value);
    }

    @Override
    public Object remove(Object key) {
        return attributes.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ? extends Object> m) {
        for (Entry<? extends String, ? extends Object> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        attributes.clear();
    }

    @Override
    public Set<String> keySet() {
        return attributes.keySet();
    }

    @Override
    public Collection<Object> values() {
        return attributes.values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return attributes.entrySet();
    }

    public boolean equals(Object o) {
        return attributes.equals(o);
    }

    public int hashCode() {
        return attributes.hashCode();
    }
}
