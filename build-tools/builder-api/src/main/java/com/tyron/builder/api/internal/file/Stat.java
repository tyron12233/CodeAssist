package com.tyron.builder.api.internal.file;

import java.io.File;

public interface Stat {
    int getUnixMode(File f) throws FileException;

    FileMetadata stat(File f) throws FileException;
}