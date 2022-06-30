package com.tyron.builder.cache.internal;

import com.tyron.builder.api.Action;

import java.io.File;

/**
 * Cache for generated jars such as {@code gradle-api-${version}.jar} and {@code gradle-test-kit-${version}.jar}.
 */
public interface GeneratedGradleJarCache {

    String CACHE_KEY = "generated-gradle-jars";

    String CACHE_DISPLAY_NAME = "Generated Gradle JARs cache";

    /**
     * Returns the generated jar uniquely identified by {@code identifier}.
     *
     * If the jar is not found in the cache the given {@code creator} action will be invoked to create it.
     *
     * @param identifier the jar identifier (for example, {@code "api"}).
     * @param creator the action that will create the file should it not be found in the cache.
     * @return the generated file.
     */
    File get(String identifier, Action<File> creator);
}
