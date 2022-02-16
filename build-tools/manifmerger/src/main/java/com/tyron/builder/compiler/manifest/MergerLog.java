package com.tyron.builder.compiler.manifest;

import androidx.annotation.NonNull;

import com.tyron.builder.model.FileAndLine;
import com.tyron.builder.log.ILogger;

/**
 * Helper to create {@link IMergerLog} instances with specific purposes
 */
public abstract class MergerLog {

    /**
     * Create a new instance of a {@link MergerLog} that prints to an {@link com.tyron.code.service.ILogger}
     *
     * @param sdkLog a non-null {@link ILogger}
     * @return A new IMergeLog
     */
    public static IMergerLog wrapSdkLog(@NonNull final ILogger sdkLog) {
        return new IMergerLog() {
            @Override
            public void error(@NonNull Severity severity, @NonNull FileAndLine location, @NonNull String message, Object... msgParams) {
                switch (severity) {
                    case INFO:
                        sdkLog.debug(message);
                        break;
                    case WARNING:
                        sdkLog.warning("Warning: " + message + ' ' + location);
                        break;
                    case ERROR:
                        sdkLog.error("Error: " + message + ' ' + location);
                }
            }

            @Override
            public void conflict(@NonNull Severity severity, @NonNull FileAndLine location1, @NonNull FileAndLine location2, @NonNull String message, Object... msgParams) {
              //  switch (severity) {
                //    case ERROR:
                        sdkLog.error(String.format(message, msgParams) + "\nlocation 1: " + location1 + "\n location 2: " + location2);
                //}
            }
        };
    }
}
