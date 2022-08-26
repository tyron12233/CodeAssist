package org.gradle.plugins.ide.internal.tooling.java;

import org.gradle.api.JavaVersion;
import org.gradle.internal.jvm.Jvm;

import java.io.File;
import java.io.Serializable;

public class DefaultInstalledJdk implements Serializable {

    private final File javaHome;
    private final JavaVersion javaVersion;

    public static DefaultInstalledJdk current() {
        Jvm current = Jvm.current();
        return new DefaultInstalledJdk(current.getJavaHome(), current.getJavaVersion());
    }

    public DefaultInstalledJdk(File javaHome, JavaVersion javaVersion) {
        this.javaHome = javaHome;
        this.javaVersion = javaVersion;
    }

    public JavaVersion getJavaVersion() {
        return javaVersion;
    }

    public File getJavaHome() {
        return javaHome;
    }
}