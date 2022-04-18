package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.RegularFileProperty;

public interface FilePropertyFactory {
    DirectoryProperty newDirectoryProperty();

    RegularFileProperty newFileProperty();
}