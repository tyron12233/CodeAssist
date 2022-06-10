package org.gradle.internal.buildevents;

import static org.gradle.internal.logging.text.StyledTextOutput.Style.*;
import static org.gradle.internal.logging.text.StyledTextOutput.Style.FailureHeader;
import static org.gradle.internal.logging.text.StyledTextOutput.Style.SuccessHeader;

import org.gradle.BuildListener;
import org.gradle.BuildResult;
import org.gradle.execution.WorkValidationWarningReporter;
import org.gradle.internal.deprecation.DeprecationLogger;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.format.DurationFormatter;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.time.Clock;
import org.gradle.api.logging.LogLevel;

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