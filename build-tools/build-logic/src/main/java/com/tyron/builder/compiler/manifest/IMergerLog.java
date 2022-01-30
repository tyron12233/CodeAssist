package com.tyron.builder.compiler.manifest;

import androidx.annotation.NonNull;

import com.tyron.builder.model.FileAndLine;

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

    public static final String MAIN_MANIFEST = "@main";

    public static final String LIBRARY = "@library";
}
