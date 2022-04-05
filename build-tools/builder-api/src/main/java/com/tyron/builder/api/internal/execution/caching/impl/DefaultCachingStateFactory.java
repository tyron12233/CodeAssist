package com.tyron.builder.api.internal.execution.caching.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.tyron.builder.api.internal.execution.caching.CachingDisabledReason;
import com.tyron.builder.api.internal.execution.caching.CachingState;
import com.tyron.builder.api.internal.execution.caching.CachingStateFactory;
import com.tyron.builder.api.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.caching.BuildCacheKey;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class DefaultCachingStateFactory implements CachingStateFactory {
    private final Logger logger;

    public DefaultCachingStateFactory(Logger logger) {
        this.logger = logger;
    }

    @Override
    public final CachingState createCachingState(BeforeExecutionState beforeExecutionState, ImmutableList<CachingDisabledReason> cachingDisabledReasons) {
        Hasher cacheKeyHasher = Hashing.md5().newHasher();

        logger.warning("Appending implementation to build cache key: " +
                       beforeExecutionState.getImplementation());
        beforeExecutionState.getImplementation().appendToHasher(cacheKeyHasher);

        beforeExecutionState.getAdditionalImplementations().forEach(additionalImplementation -> {
            logger.warning("Appending additional implementation to build cache key: " +
                    additionalImplementation);
            additionalImplementation.appendToHasher(cacheKeyHasher);
        });

        beforeExecutionState.getInputProperties().forEach((propertyName, valueSnapshot) -> {
            Hasher valueHasher = Hashing.md5().newHasher();
            valueSnapshot.appendToHasher(valueHasher);
            logger.warning("Appending input value fingerprint for '" +  propertyName +
                           "' to build cache key: " + valueHasher.hash());
            cacheKeyHasher.putString(propertyName, StandardCharsets.UTF_8);
            valueSnapshot.appendToHasher(cacheKeyHasher);
        });

        beforeExecutionState.getInputFileProperties().forEach((propertyName, fingerprint) -> {
            logger.warning("Appending input file fingerprints for '{}' to build cache key: " + propertyName + " - " + fingerprint.getHash() + "" + fingerprint);
            cacheKeyHasher.putString(propertyName, StandardCharsets.UTF_8);
            cacheKeyHasher.putBytes(fingerprint.getHash().asBytes());
        });

        beforeExecutionState.getOutputFileLocationSnapshots().keySet().forEach(propertyName -> {
            logger.warning("Appending output property name to build cache key: " + propertyName);
            cacheKeyHasher.putString(propertyName, StandardCharsets.UTF_8);
        });

        if (cachingDisabledReasons.isEmpty()) {
            return CachingState.enabled(new DefaultBuildCacheKey(cacheKeyHasher.hash()), beforeExecutionState);
        } else {
            cachingDisabledReasons.forEach(reason ->
                    logger.warning("Non-cacheable because " + reason.getMessage() + " [" + reason.getCategory() + "]"));
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