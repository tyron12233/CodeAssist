package com.tyron.builder.internal.classpath;

import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;

import java.io.File;

interface ClasspathFileTransformer {
    File transform(File source, FileSystemLocationSnapshot sourceSnapshot, File cacheDir);
}
