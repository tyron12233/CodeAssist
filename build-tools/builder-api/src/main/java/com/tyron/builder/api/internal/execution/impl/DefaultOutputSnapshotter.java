package com.tyron.builder.api.internal.execution.impl;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.execution.OutputSnapshotter;
import com.tyron.builder.api.internal.execution.UnitOfWork;
import com.tyron.builder.api.internal.execution.fingerprint.FileCollectionSnapshotter;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.api.internal.tasks.properties.TreeType;

import java.io.File;

public class DefaultOutputSnapshotter implements OutputSnapshotter {
    private final FileCollectionSnapshotter fileCollectionSnapshotter;

    public DefaultOutputSnapshotter(FileCollectionSnapshotter fileCollectionSnapshotter) {
        this.fileCollectionSnapshotter = fileCollectionSnapshotter;
    }

    @Override
    public ImmutableSortedMap<String, FileSystemSnapshot> snapshotOutputs(UnitOfWork work, File workspace) {
        ImmutableSortedMap.Builder<String, FileSystemSnapshot> builder = ImmutableSortedMap.naturalOrder();
        work.visitOutputs(workspace, new UnitOfWork.OutputVisitor() {
            @Override
            public void visitOutputProperty(String propertyName, TreeType type, File root, FileCollection contents) {
                FileSystemSnapshot snapshot;
                try {
                    snapshot = fileCollectionSnapshotter.snapshot(contents).getSnapshot();
                } catch (Exception ex) {
                    throw new OutputFileSnapshottingException(propertyName, ex);
                }
                builder.put(propertyName, snapshot);
            }
        });
        return builder.build();
    }
}