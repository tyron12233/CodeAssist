package com.tyron.builder.configuration;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.initialization.BuildClientMetaData;

import java.io.IOException;

public class GradleLauncherMetaData implements BuildClientMetaData {
    private final String appName;

    public GradleLauncherMetaData() {
        this(System.getProperty("org.gradle.appname", "gradle"));
    }

    public GradleLauncherMetaData(String appName) {
        this.appName = appName;
    }

    public String getAppName() {
        return appName;
    }

    @Override
    public void describeCommand(Appendable output, String... args) {
        try {
            output.append(appName);
            for (String arg : args) {
                output.append(' ');
                output.append(arg);
            }
        } catch (IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }
}
