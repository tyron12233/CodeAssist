package com.tyron.builder.internal.logging.events.operations;

import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.operations.logging.LogEventLevel;

import java.util.List;

/**
 * Build operation observer's view of {@link StyledTextOutputEvent}.
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