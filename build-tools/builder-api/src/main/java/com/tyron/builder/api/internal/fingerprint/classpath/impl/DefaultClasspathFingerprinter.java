package com.tyron.builder.api.internal.fingerprint.classpath.impl;

import com.tyron.builder.api.internal.changedetection.state.ResourceEntryFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceFilter;
import com.tyron.builder.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import com.tyron.builder.api.internal.changedetection.state.RuntimeClasspathResourceHasher;
import com.tyron.builder.api.internal.execution.fingerprint.FileCollectionFingerprinter;
import com.tyron.builder.api.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.api.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.api.internal.fingerprint.classpath.ClasspathFingerprinter;
import com.tyron.builder.api.internal.fingerprint.impl.AbstractFileCollectionFingerprinter;
import com.tyron.builder.api.tasks.ClasspathNormalizer;
import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.cache.StringInterner;

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

