package com.tyron.builder.api;

import static java.util.Collections.emptyList;

import com.tyron.builder.TaskExecutionRequest;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.configuration.ConsoleOutput;
import com.tyron.builder.api.logging.configuration.LoggingConfiguration;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.api.logging.configuration.WarningMode;
import com.tyron.builder.concurrent.ParallelismConfiguration;
import com.tyron.builder.initialization.BuildLayoutParameters;
import com.tyron.builder.internal.concurrent.DefaultParallelismConfiguration;
import com.tyron.builder.internal.logging.DefaultLoggingConfiguration;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>{@code StartParameter} defines the configuration used by a Gradle instance to execute a build. The properties of {@code StartParameter} generally correspond to the command-line options of
 * Gradle.
 *
 * <p>You can obtain an instance of a {@code StartParameter} by either creating a new one, or duplicating an existing one using {@link #newInstance} or {@link #newBuild}.</p>
 */
public class StartParameter implements LoggingConfiguration, ParallelismConfiguration, Serializable {
    public static final String GRADLE_USER_HOME_PROPERTY_KEY = BuildLayoutParameters.GRADLE_USER_HOME_PROPERTY_KEY;

    /**
     * The default user home directory.
     */
    public static final File DEFAULT_GRADLE_USER_HOME = new BuildLayoutParameters().getGradleUserHomeDir();

    private final DefaultLoggingConfiguration loggingConfiguration = new DefaultLoggingConfiguration();
    private final DefaultParallelismConfiguration parallelismConfiguration = new DefaultParallelismConfiguration();
    private List<TaskExecutionRequest> taskRequests = new ArrayList<>();
    private Set<String> excludedTaskNames = new LinkedHashSet<>();
    private boolean buildProjectDependencies = true;
    private File currentDir;
    private File projectDir;
    private Map<String, String> projectProperties = new HashMap<>();
    private Map<String, String> systemPropertiesArgs = new HashMap<>();
    private File gradleUserHomeDir;
    protected File gradleHomeDir;
    private File settingsFile;
    private File buildFile;
    private List<File> initScripts = new ArrayList<>();
    private boolean dryRun;
    private boolean rerunTasks;
    private boolean profile;
    private boolean continueOnFailure;
    private boolean offline;
    private File projectCacheDir;
    private boolean refreshDependencies;
    private boolean buildCacheEnabled;
    private boolean buildCacheDebugLogging;
    private boolean configureOnDemand;
    private boolean continuous;
    private List<File> includedBuilds = new ArrayList<>();
    private boolean buildScan;
    private boolean noBuildScan;
    private boolean writeDependencyLocks;
    private List<String> writeDependencyVerifications = emptyList();
    private List<String> lockedDependenciesToUpdate = emptyList();
//    private DependencyVerificationMode verificationMode = DependencyVerificationMode.STRICT;
    private boolean isRefreshKeys;
    private boolean isExportKeys;


    /**
     * Creates a {@code StartParameter} with default values. This is roughly equivalent to running Gradle on the command-line with no arguments.
     */
    public StartParameter() {
        this(new BuildLayoutParameters());
    }

    /**
     * Creates a {@code StartParameter} initialized from the given {@link BuildLayoutParameters}.
     * @since 7.0
     */
    protected StartParameter(BuildLayoutParameters layoutParameters) {
        gradleHomeDir = layoutParameters.getGradleInstallationHomeDir();
        currentDir = layoutParameters.getCurrentDir();
        projectDir = layoutParameters.getProjectDir();
        gradleUserHomeDir = layoutParameters.getGradleUserHomeDir();
    }

    @Override
    public LogLevel getLogLevel() {
        return loggingConfiguration.getLogLevel();
    }

    @Override
    public void setLogLevel(LogLevel logLevel) {
        loggingConfiguration.setLogLevel(logLevel);
    }

    @Override
    public ConsoleOutput getConsoleOutput() {
        return loggingConfiguration.getConsoleOutput();
    }

    @Override
    public void setConsoleOutput(ConsoleOutput consoleOutput) {
        loggingConfiguration.setConsoleOutput(consoleOutput);
    }

    @Override
    public WarningMode getWarningMode() {
        return loggingConfiguration.getWarningMode();
    }

    @Override
    public void setWarningMode(WarningMode warningMode) {
        loggingConfiguration.setWarningMode(warningMode);
    }

    @Override
    public ShowStacktrace getShowStacktrace() {
        return loggingConfiguration.getShowStacktrace();
    }

    @Override
    public void setShowStacktrace(ShowStacktrace showStacktrace) {
        loggingConfiguration.setShowStacktrace(showStacktrace);
    }

    @Override
    public boolean isParallelProjectExecutionEnabled() {
        return parallelismConfiguration.isParallelProjectExecutionEnabled();
    }

    @Override
    public void setParallelProjectExecutionEnabled(boolean parallelProjectExecution) {
        parallelismConfiguration.setParallelProjectExecutionEnabled(parallelProjectExecution);
    }

    @Override
    public int getMaxWorkerCount() {
        return parallelismConfiguration.getMaxWorkerCount();
    }

    @Override
    public void setMaxWorkerCount(int maxWorkerCount) {
        parallelismConfiguration.setMaxWorkerCount(maxWorkerCount);
    }

    public void setGradleUserHomeDir(File userHomeDir) {
        this.gradleUserHomeDir = userHomeDir;
    }

    public File getGradleUserHomeDir() {
        return gradleUserHomeDir;
    }

    public boolean isRerunTasks() {
        return false;
    }
}
