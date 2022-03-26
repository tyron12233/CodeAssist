package com.tyron.builder.api.internal.file.collections;
import com.tyron.builder.api.internal.nativeintegration.services.FileSystems;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;

public class FileBackedDirectoryFileTree extends DirectoryFileTree {
    private final File file;

    public FileBackedDirectoryFileTree(File file) {
        super(file.getParentFile(), new PatternSet().include(file.getName()), FileSystems.getDefault());
        this.file = file;
    }

    public File getFile() {
        return file;
    }
}