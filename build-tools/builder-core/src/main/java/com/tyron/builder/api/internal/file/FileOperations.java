package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.PathValidation;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;
import java.net.URI;
import java.util.Map;

public interface FileOperations {
    File file(Object path);

    File file(Object path, PathValidation validation);

    URI uri(Object path);

    FileResolver getFileResolver();

    String relativePath(Object path);

    /**
     * Creates a mutable file collection and initializes it with the given paths.
     */
    ConfigurableFileCollection configurableFiles(Object... paths);

    /**
     * Creates an immutable file collection with the given paths. The paths are resolved
     * with the file resolver.
     *
     * @see #getFileResolver()
     */
    FileCollection immutableFiles(Object... paths);

    ConfigurableFileTree fileTree(Object baseDir);

    ConfigurableFileTree fileTree(Map<String, ?> args);

    FileTree zipTree(Object zipPath);

    FileTree tarTree(Object tarPath);

    CopySpec copySpec();

    WorkResult copy(Action<? super CopySpec> action);

    WorkResult sync(Action<? super CopySpec> action);

    File mkdir(Object path);

    boolean delete(Object... paths);

    WorkResult delete(Action<? super DeleteSpec> action);

//    ResourceHandler getResources();

    PatternSet patternSet();
}