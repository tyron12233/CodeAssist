package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

/**
 * Java compile options.
 */
public interface JavaCompileOptions {

    /**
     * @return the java compiler encoding setting.
     */
    @NotNull
    String getEncoding();

    /**
     * @return the level of compliance Java source code has.
     */
    @NotNull
    String getSourceCompatibility();

    /**
     * @return the Java version to be able to run classes on.
     */
    @NotNull
    String getTargetCompatibility();

    /** @return true if core library desugaring is enabled, false otherwise. */
    boolean isCoreLibraryDesugaringEnabled();
}