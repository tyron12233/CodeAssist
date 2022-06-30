package com.tyron.builder.cache.internal;

import com.google.common.cache.Cache;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import java.util.logging.Logger;

class LoggingEvictionListener implements RemovalListener<Object, Object> {
    private static Logger logger = Logger.getLogger(LoggingEvictionListener.class.getSimpleName());
    private static final String EVICTION_MITIGATION_MESSAGE = "\nPerformance may suffer from in-memory cache misses. Increase max heap size of Gradle build process to reduce cache misses.";
    volatile int evictionCounter;
    private final String cacheId;
    private Cache<Object, Object> cache;
    private final int maxSize;
    private final int logInterval;

    LoggingEvictionListener(String cacheId, int maxSize) {
        this.cacheId = cacheId;
        this.maxSize = maxSize;
        this.logInterval = maxSize / 10;
    }

    public void setCache(Cache<Object, Object> cache) {
        this.cache = cache;
    }

    @Override
    public void onRemoval(RemovalNotification<Object, Object> notification) {
        if (notification.getCause() == RemovalCause.SIZE) {
            if (evictionCounter % logInterval == 0) {
                logger.info("Cache entries evicted. In-memory cache of " + cacheId + ": Size{" + cache.size() + "} MaxSize{" + maxSize + "}, " + cache.stats() + ", " + EVICTION_MITIGATION_MESSAGE);
            }
            evictionCounter++;
        }
    }
}