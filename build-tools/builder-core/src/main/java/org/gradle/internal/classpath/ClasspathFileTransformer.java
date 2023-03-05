package org.gradle.internal.classpath;

import org.gradle.internal.snapshot.FileSystemLocationSnapshot;

import java.io.File;

interface ClasspathFileTransformer {
    File transform(File source, FileSystemLocationSnapshot sourceSnapshot, File cacheDir);
}
