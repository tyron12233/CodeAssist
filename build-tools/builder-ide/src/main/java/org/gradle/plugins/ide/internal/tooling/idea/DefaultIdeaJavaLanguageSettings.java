package org.gradle.plugins.ide.internal.tooling.idea;

import org.gradle.api.JavaVersion;
import org.gradle.plugins.ide.internal.tooling.java.DefaultInstalledJdk;

import java.io.Serializable;

public class DefaultIdeaJavaLanguageSettings implements Serializable {
    private JavaVersion languageLevel;
    private JavaVersion targetBytecodeVersion;
    private DefaultInstalledJdk jdk;

    public DefaultIdeaJavaLanguageSettings setSourceLanguageLevel(JavaVersion languageLevel) {
        this.languageLevel = languageLevel;
        return this;
    }

    public DefaultIdeaJavaLanguageSettings setTargetBytecodeVersion(JavaVersion targetBytecodeVersion) {
        this.targetBytecodeVersion = targetBytecodeVersion;
        return this;
    }

    public DefaultIdeaJavaLanguageSettings setJdk(DefaultInstalledJdk jdk) {
        this.jdk = jdk;
        return this;
    }

    public JavaVersion getLanguageLevel() {
        return languageLevel;
    }

    public JavaVersion getTargetBytecodeVersion() {
        return targetBytecodeVersion;
    }

    public DefaultInstalledJdk getJdk() {
        return jdk;
    }
}