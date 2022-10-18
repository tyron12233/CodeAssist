package org.gradle.api.internal.file;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;

public interface FilePropertyFactory {
    DirectoryProperty newDirectoryProperty();

    RegularFileProperty newFileProperty();
}