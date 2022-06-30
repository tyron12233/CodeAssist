package com.tyron.builder.internal.execution.history.changes;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.incremental.InputFileDetails;
import com.tyron.builder.work.FileChange;

public class IncrementalInputChanges implements InputChangesInternal {

    private final InputFileChanges changes;
    private final IncrementalInputProperties incrementalInputProperties;

    public IncrementalInputChanges(InputFileChanges changes, IncrementalInputProperties incrementalInputProperties) {
        this.changes = changes;
        this.incrementalInputProperties = incrementalInputProperties;
    }

    @Override
    public boolean isIncremental() {
        return true;
    }

    @Override
    public Iterable<FileChange> getFileChanges(FileCollection parameter) {
        return getObjectFileChanges(parameter);
    }

    @Override
    public Iterable<FileChange> getFileChanges(Provider<? extends FileSystemLocation> parameter) {
        return getObjectFileChanges(parameter);
    }

    private Iterable<FileChange> getObjectFileChanges(Object parameter) {
        String propertyName = incrementalInputProperties.getPropertyNameFor(parameter);
        CollectingChangeVisitor visitor = new CollectingChangeVisitor();
        changes.accept(propertyName, visitor);
        return Cast.uncheckedNonnullCast(visitor.getChanges());
    }

    @Override
    public Iterable<InputFileDetails> getAllFileChanges() {
        CollectingChangeVisitor visitor = new CollectingChangeVisitor();
        changes.accept(visitor);
        return Cast.uncheckedNonnullCast(visitor.getChanges());
    }
}