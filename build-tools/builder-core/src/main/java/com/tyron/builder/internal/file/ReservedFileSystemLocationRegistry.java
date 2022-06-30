package com.tyron.builder.internal.file;

import java.io.File;

public interface ReservedFileSystemLocationRegistry {
    boolean isInReservedFileSystemLocation(File location);
}
