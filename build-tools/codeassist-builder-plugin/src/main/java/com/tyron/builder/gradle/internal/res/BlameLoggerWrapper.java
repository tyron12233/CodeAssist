package com.tyron.builder.gradle.internal.res;

import com.android.aaptcompiler.BlameLogger;
import com.android.utils.ILogger;

import org.jetbrains.annotations.NotNull;

import kotlin.jvm.functions.Function1;

public class BlameLoggerWrapper extends BlameLogger {
    public BlameLoggerWrapper(@NotNull ILogger logger,
                              @NotNull Function1<? super String, String> userVisibleSourceTransform,
                              @NotNull Function1<? super Source, Source> blameMap) {
        super(logger, userVisibleSourceTransform, blameMap);
    }

    public BlameLoggerWrapper(@NotNull ILogger logger,
                              @NotNull Function1<? super Source, Source> blameMap) {
        super(logger, blameMap);
    }
}
