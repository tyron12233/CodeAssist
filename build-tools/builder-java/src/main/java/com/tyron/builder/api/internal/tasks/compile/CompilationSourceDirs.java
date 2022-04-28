package com.tyron.builder.api.internal.tasks.compile;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.api.internal.file.collections.FileSystemMirroringFileTree;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.util.RelativePathUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Relativizes paths relative to a set of source directories in order to create a platform-independent mapping
 * from source file to class file.
 */
//@NonNullApi
public class CompilationSourceDirs {
    private static final Logger LOG = Logging.getLogger(CompilationSourceDirs.class);
    private final List<File> sourceRoots;

    public CompilationSourceDirs(JavaCompileSpec spec) {
        this.sourceRoots = new ArrayList<>(spec.getSourceRoots());
        File headerOutputDirectory = spec.getCompileOptions().getHeaderOutputDirectory();
        if (headerOutputDirectory != null) {
            sourceRoots.add(headerOutputDirectory);
        }
        File generatedSourcesDirectory = spec.getCompileOptions().getAnnotationProcessorGeneratedSourcesDirectory();
        if (generatedSourcesDirectory != null) {
            sourceRoots.add(generatedSourcesDirectory);
        }
    }

    @VisibleForTesting
    CompilationSourceDirs(List<File> sourceRoots) {
        this.sourceRoots = sourceRoots;
    }

    public static List<File> inferSourceRoots(FileTreeInternal sources) {
        SourceRoots visitor = new SourceRoots();
        sources.visitStructure(visitor);
        return visitor.canInferSourceRoots ? visitor.sourceRoots : Collections.emptyList();
    }

    /**
     * Calculate the relative path to the source root.
     */
    public Optional<String> relativize(File sourceFile) {
        return sourceRoots.stream()
                .filter(sourceDir -> sourceFile.getAbsolutePath().startsWith(sourceDir.getAbsolutePath()))
                .map(sourceDir -> RelativePathUtil.relativePath(sourceDir, sourceFile))
                .filter(relativePath -> !relativePath.startsWith(".."))
                .findFirst();
    }

    private static class SourceRoots implements FileCollectionStructureVisitor {
        private boolean canInferSourceRoots = true;
        private List<File> sourceRoots = Lists.newArrayList();

        @Override
        public void visitCollection(FileCollectionInternal.Source source, Iterable<File> contents) {
            cannotInferSourceRoots(contents);
        }

        @Override
        public void visitGenericFileTree(FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
            cannotInferSourceRoots(fileTree);
        }

        @Override
        public void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
            cannotInferSourceRoots(fileTree);
        }

        @Override
        public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
            // We need to add missing files as source roots, since the package name for deleted files provided by IncrementalTaskInputs also need to be determined.
            if (!root.exists() || root.isDirectory()) {
                sourceRoots.add(root);
            } else {
                cannotInferSourceRoots("file '" + root + "'");
            }
        }

        private void cannotInferSourceRoots(Object fileCollection) {
            canInferSourceRoots = false;
            LOG.info("Cannot infer source root(s) for source `{}`. Supported types are `File` (directories only), `DirectoryTree` and `SourceDirectorySet`.", fileCollection);
        }
    }
}
