package com.tyron.builder.tooling.internal.provider;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.execution.WorkValidationWarningReporter;
import com.tyron.builder.initialization.BuildRequestMetaData;
import com.tyron.builder.internal.buildevents.BuildLogger;
import com.tyron.builder.internal.buildevents.BuildStartedTime;
import com.tyron.builder.internal.buildtree.BuildActionRunner;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.internal.time.Clock;

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
