package com.tyron.builder.api.internal.logging.events.operations;

import com.tyron.builder.api.internal.graph.StyledTextOutput;
import com.tyron.builder.api.logging.LogLevel;

import java.util.List;

/**
 * Build operation observer's view of {@link org.gradle.internal.logging.events.StyledTextOutputEvent}.
 *
 * See LoggingBuildOperationProgressBroadcaster.
 *
 * @since 4.7
 */
//@UsedByScanPlugin("Non-internal replacement available since Gradle 7.4")
@Deprecated
@SuppressWarnings("DeprecatedIsStillUsed")
public interface StyledTextBuildOperationProgressDetails {
    LogLevel getLogLevel();


    List<? extends Span> getSpans();

    String getCategory();

    LogEventLevel getLevel();

//    @UsedByScanPlugin("Non-internal replacement available since Gradle 7.4")
    @Deprecated
    @SuppressWarnings("DeprecatedIsStillUsed")
    interface Span {
    /**
     * Always a value name of {@link StyledTextOutput.Style}.
     */
    String getStyleName();

    String getText();
    }
}