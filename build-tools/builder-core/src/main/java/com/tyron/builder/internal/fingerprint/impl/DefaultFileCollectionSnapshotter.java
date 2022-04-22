package com.tyron.builder.internal.fingerprint.impl;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.file.Stat;
import com.tyron.builder.api.internal.file.collections.FileSystemMirroringFileTree;
import com.tyron.builder.internal.fingerprint.GenericFileTreeSnapshotter;
import com.tyron.builder.internal.snapshot.CompositeFileSystemSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.vfs.FileSystemAccess;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DefaultFileCollectionSnapshotter implements FileCollectionSnapshotter {
    private final FileSystemAccess fileSystemAccess;
    private final GenericFileTreeSnapshotter genericFileTreeSnapshotter;
    private final Stat stat;

    public DefaultFileCollectionSnapshotter(FileSystemAccess fileSystemAccess, GenericFileTreeSnapshotter genericFileTreeSnapshotter, Stat stat) {
        this.fileSystemAccess = fileSystemAccess;
        this.genericFileTreeSnapshotter = genericFileTreeSnapshotter;
        this.stat = stat;
    }

    @Override
    public Result snapshot(FileCollection fileCollection) {
        SnapshottingVisitor visitor = new SnapshottingVisitor();
        ((FileCollectionInternal) fileCollection).visitStructure(visitor);
        FileSystemSnapshot snapshot = CompositeFileSystemSnapshot.of(visitor.getRoots());
        boolean fileTreeOnly = visitor.isFileTreeOnly();
        boolean containsArchiveTrees = visitor.containsArchiveTrees();
        return new Result() {
            @Override
            public FileSystemSnapshot getSnapshot() {
                return snapshot;
            }

            @Override
            public boolean isFileTreeOnly() {
                return fileTreeOnly;
            }

            @Override
            public boolean containsArchiveTrees() {
                return containsArchiveTrees;
            }
        };
    }

    private class SnapshottingVisitor implements FileCollectionStructureVisitor {
        private final List<FileSystemSnapshot> roots = new ArrayList<>();
        private Boolean fileTreeOnly;
        private boolean containsArchiveTrees;

        @Override
        public void visitCollection(FileCollectionInternal.Source source, Iterable<File> contents) {
            for (File file : contents) {
                fileSystemAccess.read(file.getAbsolutePath(), roots::add);
            }
            fileTreeOnly = false;
        }

        @Override
        public void visitGenericFileTree(FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
            roots.add(genericFileTreeSnapshotter.snapshotFileTree(fileTree));
            fileTreeOnly = false;
        }

        @Override
        public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
            fileSystemAccess.read(
                    root.getAbsolutePath(),
                    new PatternSetSnapshottingFilter(patterns, stat),
                    snapshot -> {
                        if (snapshot.getType() != FileType.Missing) {
                            roots.add(snapshot);
                        }
                    }
            );
            if (fileTreeOnly == null) {
                fileTreeOnly = true;
            }
        }

        @Override
        public void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
            fileSystemAccess.read(file.getAbsolutePath(), roots::add);
            fileTreeOnly = false;
            containsArchiveTrees = true;
        }

        public List<FileSystemSnapshot> getRoots() {
            return roots;
        }

        public boolean isFileTreeOnly() {
            return fileTreeOnly != null && fileTreeOnly;
        }

        public boolean containsArchiveTrees() {
            return containsArchiveTrees;
        }
    }
}