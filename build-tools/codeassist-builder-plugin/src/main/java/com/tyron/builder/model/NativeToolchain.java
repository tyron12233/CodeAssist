package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Toolchain for building a native library.
 */
public interface NativeToolchain {

    /**
     * Returns the name of the toolchain.
     *
     * e.g. "x86_64", "arm-linux-androideabi"
     *
     * @return name of the toolchain.
     */
    @NotNull
    String getName();

    /**
     * Returns the full path of the C compiler.
     * May be null if project do not contain C sources.
     *
     * @return the C compiler path.
     */
    @Nullable
    File getCCompilerExecutable();

    /**
     * Returns the full path of the C++ compiler.
     * May be null if project do not contain C++ sources.
     *
     * @return the C++ compiler path.
     */
    @Nullable
    File getCppCompilerExecutable();
}