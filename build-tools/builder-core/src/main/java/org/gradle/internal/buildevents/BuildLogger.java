package org.gradle.internal.buildevents;

import org.gradle.BuildListener;
import org.gradle.BuildResult;
import org.gradle.StartParameter;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.LogLevel;
import org.gradle.internal.enterprise.core.GradleEnterprisePluginManager;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.execution.TaskExecutionGraphListener;
import org.gradle.execution.WorkValidationWarningReporter;
import org.gradle.api.initialization.Settings;
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal;
import org.gradle.internal.logging.format.TersePrettyDurationFormatter;
import org.gradle.internal.logging.services.LoggingBackedStyledTextOutput;
import org.gradle.internal.logging.text.AbstractStyledTextOutputFactory;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.time.Clock;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.configuration.LoggingConfiguration;
import org.gradle.initialization.BuildRequestMetaData;
import org.gradle.internal.InternalBuildListener;

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
        exceptionReporter = new BuildExceptionReporter(textOutputFactory, loggingConfiguration, requestMetaData.getClient());
        resultLogger = new BuildResultLogger(textOutputFactory, buildStartedTime, clock, new TersePrettyDurationFormatter(), workValidationWarningReporter);
    }

    @SuppressWarnings("deprecation") // StartParameter.getSettingsFile() and StartParameter.getBuildFile()
    @Override
    public void beforeSettings(Settings settings) {
        StartParameter startParameter = settings.getStartParameter();
        logger.info("Starting Build");
        if (logger.isDebugEnabled()) {
            logger.debug("Gradle user home: {}", startParameter.getGradleUserHomeDir());
            logger.debug("Current dir: {}", startParameter.getCurrentDir());
            logger.debug("Settings file: {}", startParameter.getSettingsFile());
            logger.debug("Build file: {}", startParameter.getBuildFile());
        }
    }

    @Override
    public void settingsEvaluated(Settings settings) {
        SettingsInternal settingsInternal = (SettingsInternal) settings;
        if (logger.isInfoEnabled()) {
            logger.info("Settings evaluated using {}.",
                    settingsInternal.getSettingsScript().getDisplayName());
        }
    }

    @Override
    public void projectsLoaded(Gradle gradle) {
        if (logger.isInfoEnabled()) {
            ProjectInternal projectInternal = (ProjectInternal) gradle.getRootProject();
            logger.info("Projects loaded. Root project using {}.",
                    projectInternal.getBuildScriptSource().getDisplayName());
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
