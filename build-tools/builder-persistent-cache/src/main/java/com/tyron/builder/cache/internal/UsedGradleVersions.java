package com.tyron.builder.cache.internal;

import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.DocumentationRegistry.GradleVersion;

import java.util.SortedSet;

public interface UsedGradleVersions {

    /**
     * Returns the set of Gradle versions known to be used.
     */
    SortedSet<GradleVersion> getUsedGradleVersions();

}
