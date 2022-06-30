package com.tyron.builder.internal.file;

import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.api.provider.Provider;

public interface ReservedFileSystemLocation {
    Provider<? extends FileSystemLocation> getReservedFileSystemLocation();
}
