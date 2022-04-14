package com.tyron.builder.api.internal.tasks.compile.incremental.processing;

import java.util.Locale;

/**
 * The different kinds of annotation processors that the incremental compiler knows how to handle.
 * See the user guide chapter on incremental annotation processing for more information.
 */
public enum IncrementalAnnotationProcessorType {
    ISOLATING,
    AGGREGATING,
    DYNAMIC,
    UNKNOWN;

    public String getProcessorOption() {
        return "org.gradle.annotation.processing." + name().toLowerCase(Locale.ROOT);
    }
}
