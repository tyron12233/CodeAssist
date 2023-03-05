package org.gradle.internal.execution.impl;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.api.file.FileCollection;
import org.gradle.internal.execution.OutputSnapshotter;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.file.TreeType;
import org.gradle.internal.snapshot.FileSystemSnapshot;

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