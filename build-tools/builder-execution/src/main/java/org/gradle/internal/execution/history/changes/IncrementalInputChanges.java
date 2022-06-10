package org.gradle.internal.execution.history.changes;

import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.internal.Cast;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.incremental.InputFileDetails;
import org.gradle.work.FileChange;

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