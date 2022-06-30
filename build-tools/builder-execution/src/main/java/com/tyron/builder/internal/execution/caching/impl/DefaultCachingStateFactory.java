package com.tyron.builder.internal.execution.caching.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.tyron.builder.internal.execution.caching.CachingDisabledReason;
import com.tyron.builder.internal.execution.caching.CachingState;
import com.tyron.builder.internal.execution.caching.CachingStateFactory;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.internal.hash.Hashes;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;

public class DefaultCachingStateFactory implements CachingStateFactory {
    private final Logger logger;

    public DefaultCachingStateFactory(Logger logger) {
        this.logger = logger;
    }

    @Override
    public final CachingState createCachingState(BeforeExecutionState beforeExecutionState, ImmutableList<CachingDisabledReason> cachingDisabledReasons) {
        Hasher cacheKeyHasher = Hashing.md5().newHasher();

        logger.warn("Appending implementation to build cache key: " +
                       beforeExecutionState.getImplementation());
        beforeExecutionState.getImplementation().appendToHasher(cacheKeyHasher);

        beforeExecutionState.getAdditionalImplementations().forEach(additionalImplementation -> {
            logger.warn("Appending additional implementation to build cache key: " +
                    additionalImplementation);
            additionalImplementation.appendToHasher(cacheKeyHasher);
        });

        beforeExecutionState.getInputProperties().forEach((propertyName, valueSnapshot) -> {
            if (logger.isWarnEnabled()) {
                Hasher valueHasher = Hashes.newHasher();
                valueSnapshot.appendToHasher(valueHasher);
                logger.warn("Appending input value fingerprint for '{}' to build cache key: {}",
                        propertyName, valueHasher.hash());
            }
            cacheKeyHasher.putString(propertyName, StandardCharsets.UTF_8);
            valueSnapshot.appendToHasher(cacheKeyHasher);
        });

        beforeExecutionState.getInputFileProperties().forEach((propertyName, fingerprint) -> {
            logger.warn("Appending input file fingerprints for '{}' to build cache key: {} - {}",
                    propertyName, fingerprint.getHash(), fingerprint);
            cacheKeyHasher.putString(propertyName, StandardCharsets.UTF_8);
            Hashes.putHash(cacheKeyHasher, fingerprint.getHash());
        });

        beforeExecutionState.getOutputFileLocationSnapshots().keySet().forEach(propertyName -> {
            logger.warn("Appending output property name to build cache key: " + propertyName);
            cacheKeyHasher.putString(propertyName, StandardCharsets.UTF_8);
        });

        if (cachingDisabledReasons.isEmpty()) {
            return CachingState.enabled(new DefaultBuildCacheKey(cacheKeyHasher.hash()), beforeExecutionState);
        } else {
            cachingDisabledReasons.forEach(reason ->
                    logger.warn("Non-cacheable because " + reason.getMessage() + " [" + reason.getCategory() + "]"));
            return CachingState.disabled(cachingDisabledReasons, new DefaultBuildCacheKey(cacheKeyHasher.hash()), beforeExecutionState);
        }
    }

    private static class DefaultBuildCacheKey implements BuildCacheKey {
        private final HashCode hashCode;

        public DefaultBuildCacheKey(HashCode hashCode) {
            this.hashCode = hashCode;
        }

        @Override
        public String getHashCode() {
            return hashCode.toString();
        }

        @Override
        public byte[] toByteArray() {
            return hashCode.asBytes();
        }

        @Override
        public String getDisplayName() {
            return getHashCode();
        }

        @Override
        public String toString() {
            return getHashCode();
        }
    }
}