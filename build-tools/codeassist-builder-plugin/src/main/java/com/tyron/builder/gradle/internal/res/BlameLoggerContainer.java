package com.tyron.builder.gradle.internal.res;

import com.android.aaptcompiler.BlameLogger;
import com.android.utils.ILogger;

import kotlin.jvm.functions.Function1;

public class BlameLoggerContainer {

    private final BlameLogger blameLogger;

    private BlameLoggerContainer(BlameLogger blameLogger) {
        this.blameLogger = blameLogger;
    }

    public BlameLogger getBlameLogger() {
        return blameLogger;
    }

    public static BlameLoggerContainer create(ILogger logger, Function1<String, String> function1, Function1<BlameLogger.Source, BlameLogger.Source> sourceSourceFunction1) {
        return new BlameLoggerContainer(new BlameLogger(logger, function1, sourceSourceFunction1));
    }

    public static BlameLoggerContainer create(ILogger logger, Function1<BlameLogger.Source, BlameLogger.Source> function1) {
        return new BlameLoggerContainer(new BlameLogger(logger, function1));
    }
}
