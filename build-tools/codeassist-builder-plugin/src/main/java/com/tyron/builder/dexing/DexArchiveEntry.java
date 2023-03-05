package com.tyron.builder.dexing;

import com.google.common.base.Preconditions;
import com.android.SdkConstants;

import org.jetbrains.annotations.NotNull;

/**
 * A single DEX file in a dex archive. It is uniquely identified with {@link #relativePathInArchive}
 * within a single {@link DexArchive}. It also contains the DEX file's content ({@link
 * #dexFileContent}).
 */
public final class DexArchiveEntry {

    @NotNull
    private final byte[] dexFileContent;
    @NotNull private final String relativePathInArchive;
    @NotNull private final DexArchive dexArchive;

    public DexArchiveEntry(
            @NotNull byte[] dexFileContent,
            @NotNull String relativePathInArchive,
            @NotNull DexArchive dexArchive) {
        this.relativePathInArchive = relativePathInArchive;
        this.dexFileContent = dexFileContent;
        this.dexArchive = dexArchive;
    }

    /**
     * Takes the specified .dex file, and changes its extension to .class. It fails if invoked with
     * a file name that does not end in .dex.
     */
    @NotNull
    public static String withClassExtension(@NotNull String dexEntryPath) {
        Preconditions.checkState(
                dexEntryPath.endsWith(SdkConstants.DOT_DEX),
                "Dex archives: setting .CLASS extension only for .DEX files");

        return dexEntryPath.substring(0, dexEntryPath.length() - SdkConstants.DOT_DEX.length())
               + SdkConstants.DOT_CLASS;
    }

    /** Returns content of this DEX file. */
    @NotNull
    public byte[] getDexFileContent() {
        return dexFileContent;
    }

    /**
     * Returns a path relative to the root path of the dex archive containing it.
     *
     * @return relative path of this entry from the root of the dex archive
     */
    @NotNull
    public String getRelativePathInArchive() {
        return relativePathInArchive;
    }

    @NotNull
    public DexArchive getDexArchive() {
        return dexArchive;
    }
}