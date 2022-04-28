package com.tyron.common.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Cache maps a file + an arbitrary key to a value. When the file is modified, the mapping expires. */
public class Cache<K, V> {
    public static class Key<K> {
        public final Path file;
        public final K key;

        Key(Path file, K key) {
            this.file = file;
            this.key = key;
        }

        @Override
        public boolean equals(Object other) {
            if (other.getClass() != Cache.Key.class) return false;
            Cache.Key that = (Cache.Key) other;
            return Objects.equals(this.key, that.key) && Objects.equals(this.file, that.file);
        }

        @Override
        public int hashCode() {
            return Objects.hash(file, key);
        }
    }

    private class Value {
        final V value;
        final Instant created = Instant.now();

        Value(V value) {
            this.value = value;
        }
    }

    private final Map<Key<K>, Value> map = new HashMap<>();

    public boolean has(Path file, K k) {
        return !needs(file, k);
    }

    public void clear() {
        map.clear();
    }

    public boolean needs(Path file, K k) {
        // If key is not in map, it needs to be loaded
        Key<K> key = new Key<>(file, k);
        if (!map.containsKey(key)) return true;

        // If key was loaded before file was last modified, it needs to be reloaded
        Value value = map.get(key);
        FileTime modified = null;
        try {
            modified = Files.getLastModifiedTime(file);
        } catch (IOException e) {
            modified = FileTime.from(Instant.now());
        }
        // TODO remove all keys associated with file when file changes
        boolean before = value.created.isBefore(modified.toInstant());
        return before;
    }

    @SafeVarargs
    public final void remove(Path file, K... keys) {
        for (K k : keys) {
            Key<K> key = new Key<>(file, k);
            map.remove(key);
        }
    }

    public Set<Key<K>> getKeys() {
        return map.keySet();
    }

    public void load(Path file, K k, V v) {
        // TODO limit total size of cache
        Key<K> key = new Key<>(file, k);
        Value value = new Value(v);
        map.put(key, value);
    }

    public V get(Path file, K k) {
        Key<K> key = new Key<>(file, k);
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException(k + " is not in map " + map);
        }
        return (V) map.get(key).value;
    }
}