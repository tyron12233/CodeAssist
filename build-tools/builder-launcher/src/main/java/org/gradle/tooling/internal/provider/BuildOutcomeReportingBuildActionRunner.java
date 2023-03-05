package org.gradle.tooling.internal.provider;

import org.gradle.StartParameter;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.execution.WorkValidationWarningReporter;
import org.gradle.initialization.BuildRequestMetaData;
import org.gradle.internal.buildevents.BuildLogger;
import org.gradle.internal.buildevents.BuildStartedTime;
import org.gradle.internal.buildtree.BuildActionRunner;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.time.Clock;

public class BuildOutcomeReportingBuildActionRunner implements BuildActionRunner {
    private final ListenerManager listenerManager;
    private final BuildActionRunner delegate;
    private final BuildStartedTime buildStartedTime;
    private final BuildRequestMetaData buildRequestMetaData;
    private final Clock clock;
    private final StyledTextOutputFactory styledTextOutputFactory;
    private final WorkValidationWarningReporter workValidationWarningReporter;

    public BuildOutcomeReportingBuildActionRunner(StyledTextOutputFactory styledTextOutputFactory,
                                                  WorkValidationWarningReporter workValidationWarningReporter,
                                                  ListenerManager listenerManager,
                                                  BuildActionRunner delegate,
                                                  BuildStartedTime buildStartedTime,
                                                  BuildRequestMetaData buildRequestMetaData,
                                                  Clock clock) {
        this.styledTextOutputFactory = styledTextOutputFactory;
        this.workValidationWarningReporter = workValidationWarningReporter;
        this.listenerManager = listenerManager;
        this.delegate = delegate;
        this.buildStartedTime = buildStartedTime;
        this.buildRequestMetaData = buildRequestMetaData;
        this.clock = clock;
    }

    @Override
    public Result run(BuildAction action, BuildTreeLifecycleController buildController) {
        StartParameter startParameter = action.getStartParameter();
//        TaskExecutionStatisticsEventAdapter taskStatisticsCollector = new TaskExecutionStatisticsEventAdapter();
//        listenerManager.addListener(taskStatisticsCollector);

        BuildLogger buildLogger = new BuildLogger(
                Logging.getLogger(BuildLogger.class),
                styledTextOutputFactory,
                startParameter,
                buildRequestMetaData,
                buildStartedTime,
                clock,
                workValidationWarningReporter,
                null
        );
        // Register as a 'logger' to support this being replaced by build logic.
        buildController.beforeBuild(gradle -> gradle.useLogger(buildLogger));

        Result result = delegate.run(action, buildController);

        buildLogger.logResult(result.getBuildFailure());
//        new TaskExecutionStatisticsReporter(styledTextOutputFactory).buildFinished(taskStatisticsCollector.getStatistics());
        return result;
    }
}
