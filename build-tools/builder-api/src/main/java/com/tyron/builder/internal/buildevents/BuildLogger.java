package com.tyron.builder.internal.buildevents;

import com.tyron.builder.api.BuildListener;
import com.tyron.builder.api.BuildResult;
import com.tyron.builder.api.Gradle;
import com.tyron.builder.api.GradleEnterprisePluginManager;
import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.api.execution.TaskExecutionGraphListener;
import com.tyron.builder.api.execution.WorkValidationWarningReporter;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.logging.format.TersePrettyDurationFormatter;
import com.tyron.builder.api.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.time.Clock;
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
    public void settingsEvaluated(Object settings) {

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

    }

    @Override
    public void graphPopulated(TaskExecutionGraph graph) {
        if (logger.isInfoEnabled()) {
            logger.info("Tasks to be executed: {}", graph.getAllTasks());
            logger.info("Tasks that were excluded: {}", ((TaskExecutionGraphInternal)graph).getFilteredTasks());
        }
    }
}
