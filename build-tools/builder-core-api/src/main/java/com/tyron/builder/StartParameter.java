package com.tyron.builder;

import static java.util.Collections.emptyList;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.tyron.builder.api.artifacts.verification.DependencyVerificationMode;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.configuration.ConsoleOutput;
import com.tyron.builder.api.logging.configuration.LoggingConfiguration;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.api.logging.configuration.WarningMode;
import com.tyron.builder.util.internal.GFileUtils;
import com.tyron.builder.concurrent.ParallelismConfiguration;
import com.tyron.builder.initialization.BuildLayoutParameters;
import com.tyron.builder.internal.DefaultTaskExecutionRequest;
import com.tyron.builder.internal.concurrent.DefaultParallelismConfiguration;
import com.tyron.builder.internal.logging.DefaultLoggingConfiguration;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private DependencyVerificationMode verificationMode = DependencyVerificationMode.STRICT;
    private boolean isRefreshKeys;
    private boolean isExportKeys;
    private boolean isWriteDependencyLocks;
    private boolean isBuildProjectDependencies = true;
    private boolean isRefreshDependencies;
    private DependencyVerificationMode dependencyVerificationMode;
    private boolean exportKeys;
    private boolean isConfigureOnDemand;


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

    /**
     * Duplicates this {@code StartParameter} instance.
     *
     * @return the new parameters.
     */
    public StartParameter newInstance() {
        return prepareNewInstance(new StartParameter());
    }

    protected StartParameter prepareNewInstance(StartParameter p) {
        prepareNewBuild(p);
        p.setWarningMode(getWarningMode());
        p.buildFile = buildFile;
        p.projectDir = projectDir;
        p.settingsFile = settingsFile;
        p.taskRequests = new ArrayList<>(taskRequests);
        p.excludedTaskNames = new LinkedHashSet<>(excludedTaskNames);
        p.buildProjectDependencies = buildProjectDependencies;
        p.currentDir = currentDir;
        p.projectProperties = new HashMap<>(projectProperties);
        p.systemPropertiesArgs = new HashMap<>(systemPropertiesArgs);
        p.initScripts = new ArrayList<>(initScripts);
        p.includedBuilds = new ArrayList<>(includedBuilds);
        p.dryRun = dryRun;
        p.projectCacheDir = projectCacheDir;
        return p;
    }

    /**
     * <p>Creates the parameters for a new build, using these parameters as a template. Copies the environmental properties from this parameter (eg Gradle user home dir, etc), but does not copy the
     * build specific properties (eg task names).</p>
     *
     * @return The new parameters.
     */
    public StartParameter newBuild() {
        return prepareNewBuild(new StartParameter());
    }

    protected StartParameter prepareNewBuild(StartParameter p) {
        p.gradleUserHomeDir = gradleUserHomeDir;
        p.gradleHomeDir = gradleHomeDir;
        p.setLogLevel(getLogLevel());
        p.setConsoleOutput(getConsoleOutput());
        p.setShowStacktrace(getShowStacktrace());
        p.setWarningMode(getWarningMode());
        p.profile = profile;
        p.continueOnFailure = continueOnFailure;
        p.offline = offline;
        p.rerunTasks = rerunTasks;
        p.refreshDependencies = refreshDependencies;
        p.setParallelProjectExecutionEnabled(isParallelProjectExecutionEnabled());
        p.buildCacheEnabled = buildCacheEnabled;
        p.configureOnDemand = configureOnDemand;
        p.setMaxWorkerCount(getMaxWorkerCount());
        p.systemPropertiesArgs = new HashMap<>(systemPropertiesArgs);
        p.writeDependencyLocks = writeDependencyLocks;
        p.writeDependencyVerifications = writeDependencyVerifications;
        p.lockedDependenciesToUpdate = new ArrayList<>(lockedDependenciesToUpdate);
//        p.verificationMode = verificationMode;
        p.isRefreshKeys = isRefreshKeys;
        p.isExportKeys = isExportKeys;
        return p;
    }

    /**
     * Returns the names of the tasks to be excluded from this build. When empty, no tasks are excluded from the build.
     *
     * @return The names of the excluded tasks. Returns an empty set if there are no such tasks.
     */
    public Set<String> getExcludedTaskNames() {
        return excludedTaskNames;
    }

    /**
     * Sets the tasks to exclude from this build.
     *
     * @param excludedTaskNames The task names.
     */
    public void setExcludedTaskNames(Iterable<String> excludedTaskNames) {
        this.excludedTaskNames = Sets.newLinkedHashSet(excludedTaskNames);
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

    /**
     * Sets the directory to use to select the default project, and to search for the settings file. Set to null to use the default current directory.
     *
     * @param currentDir The directory. Set to null to use the default.
     */
    public void setCurrentDir(@Nullable File currentDir) {
        if (currentDir != null) {
            this.currentDir = GFileUtils.canonicalize(currentDir);
        } else {
            this.currentDir = new BuildLayoutParameters().getCurrentDir();
        }
    }

    public Map<String, String> getProjectProperties() {
        return projectProperties;
    }

    public void setProjectProperties(Map<String, String> projectProperties) {
        this.projectProperties = projectProperties;
    }

    public Map<String, String> getSystemPropertiesArgs() {
        return systemPropertiesArgs;
    }

    public void setSystemPropertiesArgs(Map<String, String> systemPropertiesArgs) {
        this.systemPropertiesArgs = systemPropertiesArgs;
    }

    /**
     * Adds the given file to the list of init scripts that are run before the build starts.  This list is in addition to the default init scripts.
     *
     * @param initScriptFile The init scripts.
     */
    public void addInitScript(File initScriptFile) {
        initScripts.add(initScriptFile);
    }

    /**
     * Sets the list of init scripts to be run before the build starts. This list is in addition to the default init scripts.
     *
     * @param initScripts The init scripts.
     */
    public void setInitScripts(List<File> initScripts) {
        this.initScripts = initScripts;
    }

    /**
     * Returns all explicitly added init scripts that will be run before the build starts.  This list does not contain the user init script located in ${user.home}/.gradle/init.gradle, even though
     * that init script will also be run.
     *
     * @return list of all explicitly added init scripts.
     */
    public List<File> getInitScripts() {
        return Collections.unmodifiableList(initScripts);
    }

    /**
     * Returns all init scripts, including explicit init scripts and implicit init scripts.
     *
     * @return All init scripts, including explicit init scripts and implicit init scripts.
     */
    public List<File> getAllInitScripts() {
//        CompositeInitScriptFinder initScriptFinder = new CompositeInitScriptFinder(
//                new UserHomeInitScriptFinder(getGradleUserHomeDir()),
//                new DistributionInitScriptFinder(gradleHomeDir)
//        );

        List<File> scripts = new ArrayList<>(getInitScripts());
//        initScriptFinder.findScripts(scripts);
        return Collections.unmodifiableList(scripts);
    }

    /**
     * Sets the project directory to use to select the default project. Use null to use the default criteria for selecting the default project.
     *
     * @param projectDir The project directory. May be null.
     */
    public void setProjectDir(@Nullable File projectDir) {
        if (projectDir == null) {
            setCurrentDir(null);
            this.projectDir = null;
        } else {
            File canonicalFile = GFileUtils.canonicalize(projectDir);
            currentDir = canonicalFile;
            this.projectDir = canonicalFile;
        }
    }

    /**
     * Returns the names of the tasks to execute in this build. When empty, the default tasks for the project will be executed. If {@link TaskExecutionRequest}s are set for this build then names from these task parameters are returned.
     *
     * @return the names of the tasks to execute in this build. Never returns null.
     */
    public List<String> getTaskNames() {
        List<String> taskNames = Lists.newArrayList();
        for (TaskExecutionRequest taskRequest : taskRequests) {
            taskNames.addAll(taskRequest.getArgs());
        }
        return taskNames;
    }

    /**
     * Returns the directory to use to select the default project, and to search for the settings file.
     *
     * @return The current directory. Never returns null.
     */
    public File getCurrentDir() {
        return currentDir;
    }

    /**
     * Returns the explicit settings file to use for the build, or null.
     *
     * Will return null if the default settings file is to be used.
     *
     * @return The settings file. May be null.
     *
     * @deprecated Setting custom build file to select the default project has been deprecated.
     * This method will be removed in Gradle 8.0.
     */
    @Deprecated
    @Nullable
    public File getSettingsFile() {
        return settingsFile;
    }

    public File getProjectCacheDir() {
        return projectCacheDir;
    }

    public File getBuildFile() {
        return buildFile;
    }

    public List<TaskExecutionRequest> getTaskRequests() {
        return taskRequests;
    }

    /**
     * <p>Sets the tasks to execute in this build. Set to an empty list, or null, to execute the default tasks for the project. The tasks are executed in the order provided, subject to dependency
     * between the tasks.</p>
     *
     * @param taskNames the names of the tasks to execute in this build.
     */
    public void setTaskNames(@Nullable Iterable<String> taskNames) {
        if (taskNames == null) {
            this.taskRequests = emptyList();
        } else {
            this.taskRequests = Arrays.asList(new DefaultTaskExecutionRequest(taskNames));
        }
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public boolean isContinueOnFailure() {
        return continueOnFailure;
    }

    public File getProjectDir() {
        return projectDir;
    }

    public void setSettingsFile(File o) {
        this.settingsFile = o;
    }

    public List<File> getIncludedBuilds() {
        return includedBuilds;
    }

    public void setContinuous(boolean b) {
        this.continuous = b;
    }

    public boolean isBuildCacheEnabled() {
        return buildCacheEnabled;
    }

    public void setBuildCacheEnabled(boolean enabled) {
        buildCacheEnabled = enabled;
    }

    public boolean isOffline() {
        return offline;
    }

    public boolean isBuildCacheDebugLogging() {
        return buildCacheDebugLogging;
    }

    public boolean isWriteDependencyLocks() {
        return isWriteDependencyLocks;
    }

    public boolean isBuildProjectDependencies() {
        return isBuildProjectDependencies;
    }

    public void setBuildProjectDependencies(boolean buildProjectDependencies) {
        isBuildProjectDependencies = buildProjectDependencies;
    }

    public void setWriteDependencyLocks(boolean writeDependencyLocks) {
        isWriteDependencyLocks = writeDependencyLocks;
    }

    public boolean isRefreshKeys() {
        return isRefreshKeys;
    }

    public boolean isRefreshDependencies() {
        return isRefreshDependencies;
    }

    public void setRefreshDependencies(boolean refreshDependencies) {
        isRefreshDependencies = refreshDependencies;
    }

    public List<String> getWriteDependencyVerifications() {
        return writeDependencyVerifications;
    }

    public void setWriteDependencyVerifications(List<String> writeDependencyVerifications) {
        this.writeDependencyVerifications = writeDependencyVerifications;
    }

    public DependencyVerificationMode getDependencyVerificationMode() {
        return dependencyVerificationMode;
    }

    public void setDependencyVerificationMode(DependencyVerificationMode dependencyVerificationMode) {
        this.dependencyVerificationMode = dependencyVerificationMode;
    }

    public boolean isExportKeys() {
        return exportKeys;
    }

    public void setExportKeys(boolean exportKeys) {
        this.exportKeys = exportKeys;
    }

    public List<String> getLockedDependenciesToUpdate() {
        return writeDependencyVerifications;
    }

    public void setLockedDependenciesToUpdate(List<String> lockedDependenciesToUpdate) {
        this.writeDependencyVerifications = lockedDependenciesToUpdate;
    }

    public boolean isConfigureOnDemand() {
        return isConfigureOnDemand;
    }

    public boolean isNoBuildScan() {
        return noBuildScan;
    }

    public void setNoBuildScan(boolean noBuildScan) {
        this.noBuildScan = noBuildScan;
    }

    public boolean isBuildScan() {
        return buildScan;
    }

    public void setBuildScan(boolean buildScan) {
        this.buildScan = buildScan;
    }
}
