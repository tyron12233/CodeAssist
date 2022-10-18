package org.gradle.api.internal.file;

import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;

import java.io.File;

public interface FileFactory {
    Directory dir(File dir);

    RegularFile file(File file);
}