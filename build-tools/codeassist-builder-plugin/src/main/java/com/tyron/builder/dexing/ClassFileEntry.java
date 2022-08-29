package com.tyron.builder.dexing;

import static com.tyron.builder.dexing.ClassFileInput.CLASS_MATCHER;

import com.google.common.base.Preconditions;
import com.android.SdkConstants;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * A single .class file abstraction. Relative path matches the package directory structure, and
 * convenience methods to obtain the content.
 */
public interface ClassFileEntry {

    /** Returns the entry name. */
    String name();

    /** Returns the entry size in bytes. */
    long getSize() throws IOException;

    /** Return the relative path from the root of the archive/folder abstraction. */
    String getRelativePath();

    /** Return the {@link ClassFileInput} that has produced this entry */
    @NotNull
    ClassFileInput getInput();

    /**
     * Read the content into a newly allocated byte[].
     *
     * @return file content as a byte[]
     * @throws IOException failed to read the file.
     */
    byte[] readAllBytes() throws IOException;

    /**
     * Read the content of the file into an existing byte[]
     *
     * @param bytes the bytes to read the content of the file into.
     * @return the number of bytes read.
     * @throws IOException failed to read the file or the buffer was too small.
     */
    int readAllBytes(byte[] bytes) throws IOException;

    /**
     * Takes the specified .class file, and changes its extension to .dex. It fails if invoked with
     * a file name that does not end in .class.
     */
    @NotNull
    static String withDexExtension(@NotNull String classFilePath) {
        Preconditions.checkState(
                CLASS_MATCHER.test(classFilePath),
                "Dex archives: setting .DEX extension only for .CLASS files");
        return classFilePath.substring(0, classFilePath.length() - SdkConstants.DOT_CLASS.length())
                + SdkConstants.DOT_DEX;
    }
}