package com.tyron.builder.internal.buildevents;

import com.tyron.builder.BuildListener;
import com.tyron.builder.BuildResult;
import com.tyron.builder.api.invocation.Gradle;
import com.tyron.builder.internal.enterprise.core.GradleEnterprisePluginManager;
import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.api.execution.TaskExecutionGraphListener;
import com.tyron.builder.execution.WorkValidationWarningReporter;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.execution.taskgraph.TaskExecutionGraphInternal;
import com.tyron.builder.internal.logging.format.TersePrettyDurationFormatter;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.configuration.LoggingConfiguration;
import com.tyron.builder.initialization.BuildRequestMetaData;
import com.tyron.builder.internal.InternalBuildListener;

/**
 * A {@link BuildListener} which logs the build progress.
 */
public class BuildLogger implements InternalBuildListener, TaskExecutionGraphListener {

    private final Logger logger;
    private final BuildExceptionReporter exceptionReporter;
    private final BuildResultLogger resultLogger;
    private String action;

    public BuildLogger(
            Logger logger,
            StyledTextOutputFactory textOutputFactory,
            LoggingConfiguration loggingConfiguration,
            BuildRequestMetaData requestMetaData,
            BuildStartedTime buildStartedTime,
            Clock clock,
            WorkValidationWarningReporter workValidationWarningReporter,
            GradleEnterprisePluginManager gradleEnterprisePluginManager
    ) {
        this.logger = logger;
        exceptionReporter = new BuildExceptionReporter(textOutputFactory, loggingConfiguration, requestMetaData.getClient(), gradleEnterprisePluginManager);
        resultLogger = new BuildResultLogger(textOutputFactory, buildStartedTime, clock, new TersePrettyDurationFormatter(), workValidationWarningReporter);
    }

    @Override
    public void settingsEvaluated(Settings settings) {

    }

    @Override
    public void projectsLoaded(Gradle gradle) {
        if (logger.isInfoEnabled()) {
            ProjectInternal projectInternal = (ProjectInternal) gradle.getRootProject();
            logger.info("Projects loaded. Root project using {}.",
                    projectInternal.getDisplayName());
            logger.info("Included projects: {}", projectInternal.getAllprojects());
        }
    }

    @Override
    public void projectsEvaluated(Gradle gradle) {
        logger.info("All projects evaluated.");
    }

    @Override
    public void buildFinished(BuildResult result) {
        this.action = result.getAction();
    }

    @Override
    public void graphPopulated(TaskExecutionGraph graph) {
        if (logger.isInfoEnabled()) {
            logger.info("Tasks to be executed: {}", graph.getAllTasks());
            logger.info("Tasks that were excluded: {}", ((TaskExecutionGraphInternal)graph).getFilteredTasks());
        }
    }

    public void logResult(Throwable buildFailure) {
        if (action == null) {
            // This logger has been replaced (for example using `Gradle.useLogger()`), so don't log anything
            return;
        }
        BuildResult buildResult = new BuildResult(action, null, buildFailure);
        exceptionReporter.buildFinished(buildResult);
        resultLogger.buildFinished(buildResult);
    }
}
