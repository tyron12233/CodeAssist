package com.tyron.builder.internal.fingerprint.classpath.impl;

import com.tyron.builder.api.internal.changedetection.state.AbiExtractingClasspathResourceHasher;
import com.tyron.builder.api.internal.changedetection.state.CachingResourceHasher;
import com.tyron.builder.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.internal.fingerprint.classpath.CompileClasspathFingerprinter;
import com.tyron.builder.internal.fingerprint.impl.AbstractFileCollectionFingerprinter;
import com.tyron.builder.api.tasks.CompileClasspathNormalizer;
import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.api.internal.cache.StringInterner;

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