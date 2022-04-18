package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.RegularFile;

import java.io.File;

public interface FileFactory {
    Directory dir(File dir);

    RegularFile file(File file);
}