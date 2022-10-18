package org.gradle.cache.internal.btree;


class CorruptedCacheException extends RuntimeException {
    CorruptedCacheException(String message) {
        super(message);
    }
}