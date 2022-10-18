package org.gradle.internal.fingerprint.classpath.impl;

import org.gradle.api.internal.changedetection.state.ResourceEntryFilter;
import org.gradle.api.internal.changedetection.state.ResourceFilter;
import org.gradle.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import org.gradle.api.internal.changedetection.state.RuntimeClasspathResourceHasher;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.fingerprint.LineEndingSensitivity;
import org.gradle.internal.fingerprint.classpath.ClasspathFingerprinter;
import org.gradle.internal.fingerprint.impl.AbstractFileCollectionFingerprinter;
import org.gradle.api.tasks.ClasspathNormalizer;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.api.internal.cache.StringInterner;

import java.util.Map;

public class DefaultClasspathFingerprinter extends AbstractFileCollectionFingerprinter implements ClasspathFingerprinter {
    public DefaultClasspathFingerprinter(
            ResourceSnapshotterCacheService cacheService,
            FileCollectionSnapshotter fileCollectionSnapshotter,
            ResourceFilter classpathResourceFilter,
            ResourceEntryFilter manifestAttributeResourceEntryFilter,
            Map<String, ResourceEntryFilter> propertiesFileFilters,
            StringInterner stringInterner,
            LineEndingSensitivity lineEndingSensitivity
    ) {
        super(
                ClasspathFingerprintingStrategy.runtimeClasspath(
                        classpathResourceFilter,
                        manifestAttributeResourceEntryFilter,
                        propertiesFileFilters,
                        new RuntimeClasspathResourceHasher(),
                        cacheService,
                        stringInterner,
                        lineEndingSensitivity
                ),
                fileCollectionSnapshotter
        );
    }

    @Override
    public Class<? extends FileNormalizer> getRegisteredType() {
        return ClasspathNormalizer.class;
    }
}

