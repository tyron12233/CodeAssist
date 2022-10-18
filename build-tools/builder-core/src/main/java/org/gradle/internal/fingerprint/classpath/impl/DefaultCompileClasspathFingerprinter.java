package org.gradle.internal.fingerprint.classpath.impl;

import org.gradle.api.internal.changedetection.state.AbiExtractingClasspathResourceHasher;
import org.gradle.api.internal.changedetection.state.CachingResourceHasher;
import org.gradle.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.fingerprint.classpath.CompileClasspathFingerprinter;
import org.gradle.internal.fingerprint.impl.AbstractFileCollectionFingerprinter;
import org.gradle.api.tasks.CompileClasspathNormalizer;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.api.internal.cache.StringInterner;

public class DefaultCompileClasspathFingerprinter extends AbstractFileCollectionFingerprinter implements CompileClasspathFingerprinter {
    public DefaultCompileClasspathFingerprinter(ResourceSnapshotterCacheService cacheService, FileCollectionSnapshotter fileCollectionSnapshotter, StringInterner stringInterner) {
        super(ClasspathFingerprintingStrategy.compileClasspath(
                new CachingResourceHasher(new AbiExtractingClasspathResourceHasher(), cacheService),
                cacheService,
                stringInterner
        ), fileCollectionSnapshotter);
    }

    @Override
    public Class<? extends FileNormalizer> getRegisteredType() {
        return CompileClasspathNormalizer.class;
    }
}