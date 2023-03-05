package org.gradle.launcher.exec;

import org.gradle.api.logging.LogLevel;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.logging.events.OutputEventListener;

import java.io.File;
import java.util.Map;

public interface BuildActionParameters {
    Map<String, String> getSystemProperties();

    Map<String, String> getEnvVariables();

    File getCurrentDir();

    LogLevel getLogLevel();

    boolean isUseDaemon();

    ClassPath getInjectedPluginClasspath();

    // TODO: CodeAssist added
    default OutputEventListener getOutputEventListener() {
        return null;
    }
}
