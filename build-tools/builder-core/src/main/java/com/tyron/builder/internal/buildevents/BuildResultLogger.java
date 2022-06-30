package com.tyron.builder.internal.buildevents;

import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.*;
import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.FailureHeader;
import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.SuccessHeader;

import com.tyron.builder.BuildListener;
import com.tyron.builder.BuildResult;
import com.tyron.builder.execution.WorkValidationWarningReporter;
import com.tyron.builder.internal.deprecation.DeprecationLogger;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.logging.format.DurationFormatter;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.api.logging.LogLevel;

/**
 * A {@link BuildListener} which logs the final result of the build.
 */
public class BuildResultLogger {

    private final StyledTextOutputFactory textOutputFactory;
    private final BuildStartedTime buildStartedTime;
    private final DurationFormatter durationFormatter;
    private final WorkValidationWarningReporter workValidationWarningReporter;
    private final Clock clock;

    public BuildResultLogger(
            StyledTextOutputFactory textOutputFactory,
            BuildStartedTime buildStartedTime,
            Clock clock,
            DurationFormatter durationFormatter,
            WorkValidationWarningReporter workValidationWarningReporter
    ) {
        this.textOutputFactory = textOutputFactory;
        this.buildStartedTime = buildStartedTime;
        this.clock = clock;
        this.durationFormatter = durationFormatter;
        this.workValidationWarningReporter = workValidationWarningReporter;
    }

    public void buildFinished(BuildResult result) {
        // Summary of deprecations is considered a part of the build summary
        DeprecationLogger.reportSuppressedDeprecations();

        // Summary of validation warnings during the build
        workValidationWarningReporter.reportWorkValidationWarningsAtEndOfBuild();

        boolean buildSucceeded = result.getFailure() == null;

        StyledTextOutput
                textOutput = textOutputFactory.create(BuildResultLogger.class, buildSucceeded ? LogLevel.LIFECYCLE : LogLevel.ERROR);
        textOutput.println();
        String action = result.getAction().toUpperCase();
        if (buildSucceeded) {
            textOutput.withStyle(StyledTextOutput.Style.SuccessHeader).text(action + " SUCCESSFUL");
        } else {
            textOutput.withStyle(FailureHeader).text(action + " FAILED");
        }

        long buildDurationMillis = clock.getCurrentTime() - buildStartedTime.getStartTime();
        textOutput.formatln(" in %s", durationFormatter.format(buildDurationMillis));
    }
}