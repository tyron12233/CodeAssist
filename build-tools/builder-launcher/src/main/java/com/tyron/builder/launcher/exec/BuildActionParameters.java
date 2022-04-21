package com.tyron.builder.launcher.exec;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.classpath.ClassPath;

import java.io.File;
import java.util.Map;

public interface BuildActionParameters {
    Map<String, String> getSystemProperties();

    Map<String, String> getEnvVariables();

    File getCurrentDir();

    LogLevel getLogLevel();

    boolean isUseDaemon();

    ClassPath getInjectedPluginClasspath();
}
