package com.tyron.code.compiler.manifest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Logger interface for the {@link ManifestMerger}
 */
public interface IMergerLog {

    /** Severity of the error message */
    public enum Severity {
        /**
         * A very low severity information, This does not stop processing
         * Clients might want to have a "not verbose" flag to not display this
         */
        INFO,
        /**
         * Warning, this does not stop processing
         */
        WARNING,
        /**
         * A fatal error.
         * The merger does not stop on errors, in an attempt to accumulate as much
         * info as possible to return to the user. However in case even on error
         * is generated the output should not be used, if any.
         */
        ERROR
    }

    public abstract void error (
            @NonNull Severity severity,
            @NonNull FileAndLine location,
            @NonNull String message,
            Object... msgParams);

    public abstract void conflict(
            @NonNull Severity severity,
            @NonNull FileAndLine location1,
            @NonNull FileAndLine location2,
            @NonNull String message,
            Object... msgParams);

    /**
     * Information about the file and line number where an error occured.
     */
    public static class FileAndLine {
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

        @NonNull
        @Override
        public String toString() {
            String name = mFilePath;
            if (MAIN_MANIFEST.equals(name)) {
                name = "main manifest";
            } else if (LIBRARY.equals(name)) {
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

    public static final String MAIN_MANIFEST = "@main";

    public static final String LIBRARY = "@library";
}
