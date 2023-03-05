package org.gradle.cache.internal;

import org.gradle.util.GradleVersion;

import java.util.SortedSet;

public interface UsedGradleVersions {

    /**
     * Returns the set of Gradle versions known to be used.
     */
    SortedSet<GradleVersion> getUsedGradleVersions();

}
