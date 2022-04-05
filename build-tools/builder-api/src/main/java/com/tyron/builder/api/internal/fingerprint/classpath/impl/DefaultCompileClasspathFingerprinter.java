package com.tyron.builder.api.internal.fingerprint.classpath.impl;

import com.tyron.builder.api.internal.changedetection.state.ResourceSnapshotterCacheService;
import com.tyron.builder.api.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.api.internal.fingerprint.classpath.CompileClasspathFingerprinter;
import com.tyron.builder.api.internal.fingerprint.impl.AbstractFileCollectionFingerprinter;
import com.tyron.builder.api.tasks.CompileClasspathNormalizer;
import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.cache.StringInterner;

//public class DefaultCompileClasspathFingerprinter extends AbstractFileCollectionFingerprinter implements CompileClasspathFingerprinter {
//    public DefaultCompileClasspathFingerprinter(ResourceSnapshotterCacheService cacheService, FileCollectionSnapshotter fileCollectionSnapshotter, StringInterner stringInterner) {
//        super(ClasspathFingerprintingStrategy.compileClasspath(
//                new CachingResourceHasher(new AbiExtractingClasspathResourceHasher(), cacheService),
//                cacheService,
//                stringInterner
//        ), fileCollectionSnapshotter);
//    }
//
//    @Override
//    public Class<? extends FileNormalizer> getRegisteredType() {
//        return CompileClasspathNormalizer.class;
//    }
//}