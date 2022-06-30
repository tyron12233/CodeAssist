package com.tyron.builder.cache.internal.btree;


class CorruptedCacheException extends RuntimeException {
    CorruptedCacheException(String message) {
        super(message);
    }
}