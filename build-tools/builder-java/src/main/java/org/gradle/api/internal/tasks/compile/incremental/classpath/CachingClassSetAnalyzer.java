package org.gradle.api.internal.tasks.compile.incremental.classpath;

import com.google.common.hash.HashCode;
import org.gradle.api.internal.tasks.compile.incremental.deps.ClassSetAnalysisData;
import org.gradle.cache.Cache;
import org.gradle.internal.vfs.FileSystemAccess;

import java.io.File;

public class CachingClassSetAnalyzer implements ClassSetAnalyzer {

    private final ClassSetAnalyzer delegate;
    private final FileSystemAccess fileSystemAccess;
    private final Cache<HashCode, ClassSetAnalysisData> cache;

    public CachingClassSetAnalyzer(ClassSetAnalyzer delegate,
                                   FileSystemAccess fileSystemAccess,
                                   Cache<HashCode, ClassSetAnalysisData> cache) {
        this.delegate = delegate;
        this.fileSystemAccess = fileSystemAccess;
        this.cache = cache;
    }

    @Override
    public ClassSetAnalysisData analyzeClasspathEntry(final File classpathEntry) {
        return fileSystemAccess.read(
                classpathEntry.getAbsolutePath(),
                snapshot -> cache.get(snapshot.getHash(), hash -> delegate.analyzeClasspathEntry(classpathEntry))
        );
    }

    @Override
    public ClassSetAnalysisData analyzeOutputFolder(File outputFolder) {
        return delegate.analyzeOutputFolder(outputFolder);
    }
}

