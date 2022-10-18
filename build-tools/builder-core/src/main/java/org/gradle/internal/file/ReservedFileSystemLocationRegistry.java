package org.gradle.internal.file;

import java.io.File;

public interface ReservedFileSystemLocationRegistry {
    boolean isInReservedFileSystemLocation(File location);
}
