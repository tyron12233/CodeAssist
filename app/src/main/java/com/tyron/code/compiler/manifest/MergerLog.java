package com.tyron.code.compiler.manifest;

import androidx.annotation.NonNull;

import com.tyron.code.service.ILogger;

/**
 * Helper to create {@link IMergerLog} instances with specific purposes
 */
public abstract class MergerLog {

    /**
     * Create a new instance of a {@link MergerLog} that prints to an {@link com.tyron.code.service.ILogger}
     *
     * @param sdkLog a non-null {@link com.tyron.code.service.ILogger}
     * @return A new IMergeLog
     */
    public static IMergerLog wrapSdkLog(@NonNull final ILogger sdkLog) {
        return new IMergerLog() {
            @Override
            public void error(@NonNull Severity severity, @NonNull FileAndLine location, @NonNull String message, Object... msgParams) {
                throw new UnsupportedOperationException("Not yet implemented");
            }

            @Override
            public void conflict(@NonNull Severity severity, @NonNull FileAndLine location1, @NonNull FileAndLine location2, @NonNull String message, Object... msgParams) {

            }
        };
    }
}
