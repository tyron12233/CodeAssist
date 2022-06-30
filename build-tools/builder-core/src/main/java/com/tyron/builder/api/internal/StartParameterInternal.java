package com.tyron.builder.api.internal;

import com.tyron.builder.StartParameter;
import com.tyron.builder.initialization.BuildLayoutParameters;
import com.tyron.builder.internal.buildoption.BuildOption;
import com.tyron.builder.internal.watch.registry.WatchMode;

import java.io.File;
import java.time.Duration;

public class StartParameterInternal extends StartParameter {

    private WatchMode watchFileSystemMode = WatchMode.DEFAULT;
    private boolean watchFileSystemDebugLogging;
    private boolean vfsVerboseLogging;

    private BuildOption.Value<Boolean> configurationCache = BuildOption.Value.defaultValue(false);
    private BuildOption.Value<Boolean> isolatedProjects = BuildOption.Value.defaultValue(false);
//    private ConfigurationCacheProblemsOption.Value configurationCacheProblems = ConfigurationCacheProblemsOption.Value.FAIL;
    private boolean configurationCacheDebug;
    private int configurationCacheMaxProblems = 512;
    private boolean configurationCacheRecreateCache;
    private boolean configurationCacheQuiet;
    private boolean searchUpwards = true;
    private boolean useEmptySettings = false;
    private Duration continuousBuildQuietPeriod = Duration.ofMillis(250);

    public StartParameterInternal() {
    }

    protected StartParameterInternal(BuildLayoutParameters layoutParameters) {
        super(layoutParameters);
    }

    @Override
    public StartParameterInternal newInstance() {
        return (StartParameterInternal) prepareNewInstance(new StartParameterInternal());
    }

    @Override
    public StartParameterInternal newBuild() {
        return prepareNewBuild(new StartParameterInternal());
    }

    @Override
    protected StartParameterInternal prepareNewBuild(StartParameter startParameter) {
        StartParameterInternal p = (StartParameterInternal) super.prepareNewBuild(startParameter);
        p.watchFileSystemMode = watchFileSystemMode;
        p.watchFileSystemDebugLogging = watchFileSystemDebugLogging;
        p.vfsVerboseLogging = vfsVerboseLogging;
        p.configurationCache = configurationCache;
        p.isolatedProjects = isolatedProjects;
//        p.configurationCacheProblems = configurationCacheProblems;
        p.configurationCacheMaxProblems = configurationCacheMaxProblems;
        p.configurationCacheDebug = configurationCacheDebug;
        p.configurationCacheRecreateCache = configurationCacheRecreateCache;
        p.configurationCacheQuiet = configurationCacheQuiet;
        p.searchUpwards = searchUpwards;
        p.useEmptySettings = useEmptySettings;
        return p;
    }

    public File getGradleHomeDir() {
        return gradleHomeDir;
    }

    public void setGradleHomeDir(File gradleHomeDir) {
        this.gradleHomeDir = gradleHomeDir;
    }

    public boolean isSearchUpwards() {
        return searchUpwards;
    }

    public void doNotSearchUpwards() {
        this.searchUpwards = false;
    }

    public boolean isUseEmptySettings() {
        return useEmptySettings;
    }

    public void useEmptySettings() {
        this.useEmptySettings = true;
    }

    public WatchMode getWatchFileSystemMode() {
        return watchFileSystemMode;
    }

    public void setWatchFileSystemMode(WatchMode watchFileSystemMode) {
        this.watchFileSystemMode = watchFileSystemMode;
    }

    public boolean isWatchFileSystemDebugLogging() {
        return watchFileSystemDebugLogging;
    }

    public void setWatchFileSystemDebugLogging(boolean watchFileSystemDebugLogging) {
        this.watchFileSystemDebugLogging = watchFileSystemDebugLogging;
    }

    public boolean isVfsVerboseLogging() {
        return vfsVerboseLogging;
    }

    public void setVfsVerboseLogging(boolean vfsVerboseLogging) {
        this.vfsVerboseLogging = vfsVerboseLogging;
    }

    /**
     * Used by the Kotlin plugin, via reflection.
     */
    @Deprecated
    public boolean isConfigurationCache() {
        return getConfigurationCache().get();
    }

    /**
     * Is the configuration cache requested? Note: depending on the build action, this may not be the final value for this option.
     *
     * Consider querying {@link BuildModelParameters} instead.
     */
    public BuildOption.Value<Boolean> getConfigurationCache() {
        return configurationCache;
    }

    public void setConfigurationCache(BuildOption.Value<Boolean> configurationCache) {
        this.configurationCache = configurationCache;
    }

    public BuildOption.Value<Boolean> getIsolatedProjects() {
        return isolatedProjects;
    }

    public void setIsolatedProjects(BuildOption.Value<Boolean> isolatedProjects) {
        this.isolatedProjects = isolatedProjects;
    }

//    public ConfigurationCacheProblemsOption.Value getConfigurationCacheProblems() {
//        return configurationCacheProblems;
//    }
//
//    public void setConfigurationCacheProblems(ConfigurationCacheProblemsOption.Value configurationCacheProblems) {
//        this.configurationCacheProblems = configurationCacheProblems;
//    }

    public boolean isConfigurationCacheDebug() {
        return configurationCacheDebug;
    }

    public void setConfigurationCacheDebug(boolean configurationCacheDebug) {
        this.configurationCacheDebug = configurationCacheDebug;
    }

    public int getConfigurationCacheMaxProblems() {
        return configurationCacheMaxProblems;
    }

    public void setConfigurationCacheMaxProblems(int configurationCacheMaxProblems) {
        this.configurationCacheMaxProblems = configurationCacheMaxProblems;
    }

    public boolean isConfigurationCacheRecreateCache() {
        return configurationCacheRecreateCache;
    }

    public void setConfigurationCacheRecreateCache(boolean configurationCacheRecreateCache) {
        this.configurationCacheRecreateCache = configurationCacheRecreateCache;
    }

    public boolean isConfigurationCacheQuiet() {
        return configurationCacheQuiet;
    }

    public void setConfigurationCacheQuiet(boolean configurationCacheQuiet) {
        this.configurationCacheQuiet = configurationCacheQuiet;
    }

    public void setContinuousBuildQuietPeriod(Duration continuousBuildQuietPeriod) {
        this.continuousBuildQuietPeriod = continuousBuildQuietPeriod;
    }

    public Duration getContinuousBuildQuietPeriod() {
        return continuousBuildQuietPeriod;
    }
}
