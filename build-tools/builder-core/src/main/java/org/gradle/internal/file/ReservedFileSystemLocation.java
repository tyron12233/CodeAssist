package org.gradle.internal.file;

import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;

public interface ReservedFileSystemLocation {
    Provider<? extends FileSystemLocation> getReservedFileSystemLocation();
}
