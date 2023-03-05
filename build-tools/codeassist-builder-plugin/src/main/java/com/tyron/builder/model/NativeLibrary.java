package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

/**
 * A Native CodeAssistLibrary. The configurations used to create a shared object.
 *
 * <p>Deprecated since ndk-compile is deprecated.
 */
@Deprecated
public interface NativeLibrary {

    /**
     * Returns the name of the native library.
     *
     * A native library "libfoo.so" would have the name of "foo".
     *
     * @return name of the native library.
     */
    @NotNull
    String getName();

    /**
     * Returns the ABI of the library.
     *
     * @return abi of the library.
     */
    @NotNull
    String getAbi();

    /**
     * Returns the name of the toolchain used to compile the native library.
     *
     * @return name of the toolchain.
     */
    @NotNull
    String getToolchainName();

    /**
     * A list of include directories for compiling C code.
     *
     * @return list of include directories.
     */
    @NotNull
    List<File> getCIncludeDirs();

    /**
     * A list of include directories for compiling C++ code.
     *
     * @return list of include directories.
     */
    @NotNull
    List<File> getCppIncludeDirs();

    /**
     * A list of system include directories for compiling C code.
     *
     * @return list of include directories.
     */
    @NotNull
    List<File> getCSystemIncludeDirs();

    /**
     * A list of system include directories for compiling C++ code.
     *
     * @return list of include directories.
     */
    @NotNull
    List<File> getCppSystemIncludeDirs();

    /**
     * A list of defines for C code.
     *
     * @return list of defines.
     */
    @NotNull
    List<String> getCDefines();

    /**
     * A list of defines for C++ code.
     *
     * @return list of defines.
     */
    @NotNull
    List<String> getCppDefines();

    /**
     * A list of compiler flags for C code.
     *
     * @return list of compiler flags.
     */
    @NotNull
    List<String> getCCompilerFlags();

    /**
     * A list of compiler flags for C++ code.
     *
     * @return list of compiler flags.
     */
    @NotNull
    List<String> getCppCompilerFlags();

    /**
     * The folders containing built libraries with debug information.
     *
     * @return list of paths to locate shared objects with debug information.
     */
    @NotNull
    List<File> getDebuggableLibraryFolders();

}