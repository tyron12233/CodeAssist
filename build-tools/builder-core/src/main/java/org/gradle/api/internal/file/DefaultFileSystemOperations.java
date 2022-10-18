package org.gradle.api.internal.file;

import org.gradle.api.Action;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.DeleteSpec;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.WorkResult;


public class DefaultFileSystemOperations implements FileSystemOperations {

    private final FileOperations fileOperations;

    public DefaultFileSystemOperations(FileOperations fileOperations) {
        this.fileOperations = fileOperations;
    }

    @Override
    public WorkResult copy(Action<? super CopySpec> action) {
        return fileOperations.copy(action);
    }

    @Override
    public WorkResult sync(Action<? super CopySpec> action) {
        return fileOperations.sync(action);
    }

    @Override
    public WorkResult delete(Action<? super DeleteSpec> action) {
        return fileOperations.delete(action);
    }
}
