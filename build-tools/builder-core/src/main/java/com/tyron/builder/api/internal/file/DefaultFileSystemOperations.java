package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.file.DeleteSpec;
import com.tyron.builder.api.file.FileSystemOperations;
import com.tyron.builder.api.tasks.WorkResult;


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
