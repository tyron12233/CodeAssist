package org.gradle.plugins.ide.internal.tooling.model;

import org.gradle.tooling.model.gradle.GradleScript;

import java.io.File;
import java.io.Serializable;

public class DefaultGradleScript implements GradleScript, Serializable {
    private File sourceFile;

    @Override
    public File getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(File sourceFile) {
        this.sourceFile = sourceFile;
    }
}