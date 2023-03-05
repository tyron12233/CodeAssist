package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

/**
 * A Java library.
 */
public interface JavaLibrary extends Library {
    /**
     * Returns the library's jar file.
     */
    @NotNull
    File getJarFile();

    /**
     * Returns the direct dependencies of this library.
     */
    @NotNull
    List<? extends JavaLibrary> getDependencies();
}