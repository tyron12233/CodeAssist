package com.tyron.builder.launcher.exec;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.util.GUtil;

import java.io.File;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class DefaultBuildActionParameters implements BuildActionParameters, Serializable {
    private final File currentDir;
    private final LogLevel logLevel;
    private final Map<String, String> systemProperties;
    private final Map<String, String> envVariables;

    private final boolean useDaemon;
    private final ClassPath injectedPluginClasspath;

    public DefaultBuildActionParameters(Map<?, ?> systemProperties, Map<String, String> envVariables, File currentDir, LogLevel logLevel, boolean useDaemon, ClassPath injectedPluginClasspath) {
        this.currentDir = currentDir;
        this.logLevel = logLevel;
        this.useDaemon = useDaemon;
        assert systemProperties != null;
        assert envVariables != null;
        this.systemProperties = new HashMap<String, String>();
        GUtil.addToMap(this.systemProperties, systemProperties);
        this.envVariables = new HashMap<String, String>(envVariables);
        this.injectedPluginClasspath = injectedPluginClasspath;
    }

    @Override
    public Map<String, String> getSystemProperties() {
        return systemProperties;
    }

    @Override
    public Map<String, String> getEnvVariables() {
        return envVariables;
    }

    @Override
    public File getCurrentDir() {
        return currentDir;
    }

    @Override
    public LogLevel getLogLevel() {
        return logLevel;
    }

    @Override
    public String toString() {
        return "DefaultBuildActionParameters{"
            + ", currentDir=" + currentDir
            + ", systemProperties size=" + systemProperties.size()
            + ", envVariables size=" + envVariables.size()
            + ", logLevel=" + logLevel
            + ", useDaemon=" + useDaemon
            + ", injectedPluginClasspath=" + injectedPluginClasspath
            + '}';
    }

    @Override
    public boolean isUseDaemon() {
        return useDaemon;
    }

    @Override
    public ClassPath getInjectedPluginClasspath() {
        return injectedPluginClasspath;
    }
}
