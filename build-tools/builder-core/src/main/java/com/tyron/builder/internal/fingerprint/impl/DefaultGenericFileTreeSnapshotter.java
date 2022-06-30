package com.tyron.builder.internal.fingerprint.impl;

import com.google.common.collect.Interner;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileTreeInternal;
import com.tyron.builder.internal.file.impl.DefaultFileMetadata;
import com.tyron.builder.internal.fingerprint.GenericFileTreeSnapshotter;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;

import java.nio.file.Files;
import java.nio.file.Paths;

public class DefaultGenericFileTreeSnapshotter implements GenericFileTreeSnapshotter {

    private final FileHasher hasher;
    private final Interner<String> stringInterner;

    public DefaultGenericFileTreeSnapshotter(FileHasher hasher, Interner<String> stringInterner) {
        this.hasher = hasher;
        this.stringInterner = stringInterner;
    }

    @Override
    public FileSystemSnapshot snapshotFileTree(FileTreeInternal tree) {
        FileSystemSnapshotBuilder builder = new FileSystemSnapshotBuilder(stringInterner, hasher);
        tree.visit(new FileVisitor() {
            @Override
            public void visitDir(FileVisitDetails dirDetails) {
                builder.addDir(
                        dirDetails.getFile(),
                        dirDetails.getRelativePath().getSegments()
                );
            }

            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                builder.addFile(
                        fileDetails.getFile(),
                        fileDetails.getRelativePath().getSegments(),
                        fileDetails.getName(),
                        DefaultFileMetadata.file(
                                fileDetails.getLastModified(),
                                fileDetails.getSize(),
                                FileMetadata.AccessType.viaSymlink(Files.isSymbolicLink(Paths.get(fileDetails.getPath())))
                        )
                );
            }
        });
        return builder.build();
    }
}