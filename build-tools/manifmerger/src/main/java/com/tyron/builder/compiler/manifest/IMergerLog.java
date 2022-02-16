package com.tyron.builder.compiler.manifest;

import org.jetbrains.annotations.NotNull;

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
            @NotNull Severity severity,
            @NotNull FileAndLine location,
            @NotNull String message,
            Object... msgParams);

    public abstract void conflict(
            @NotNull Severity severity,
            @NotNull FileAndLine location1,
            @NotNull FileAndLine location2,
            @NotNull String message,
            Object... msgParams);

    public static final String MAIN_MANIFEST = "@main";

    public static final String LIBRARY = "@library";
}
