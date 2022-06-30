package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.IMergerLog;

/**
 * Information about the file and line number where an error occurred.
 */
public class FileAndLine {
    private final String mFilePath;
    private final int mLine;

    public FileAndLine(@Nullable String filePath, int line) {
        mFilePath = filePath;
        mLine = line;
    }

    @Nullable
    public String getFileName() {
        return mFilePath;
    }

    public int getLine() {
        return mLine;
    }

    @NotNull
    @Override
    public String toString() {
        String name = mFilePath;
        if (IMergerLog.MAIN_MANIFEST.equals(name)) {
            name = "main manifest";
        } else if (IMergerLog.LIBRARY.equals(name)) {
            name = "library";
        } else if (name == null) {
            name = "(Unknown)";
        }
        if (mLine <= 0) {
            return name;
        } else {
            return name + ':' + mLine;
        }
    }
}
