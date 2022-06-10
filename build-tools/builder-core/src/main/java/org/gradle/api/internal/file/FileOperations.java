package org.gradle.api.internal.file;

import org.gradle.api.Action;
import org.gradle.api.PathValidation;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.DeleteSpec;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.resources.ResourceHandler;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.util.PatternSet;

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

    ResourceHandler getResources();
}