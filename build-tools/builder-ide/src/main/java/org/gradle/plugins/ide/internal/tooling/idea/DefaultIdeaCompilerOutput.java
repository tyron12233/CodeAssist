package org.gradle.plugins.ide.internal.tooling.idea;

import org.gradle.tooling.model.idea.IdeaCompilerOutput;

import java.io.File;
import java.io.Serializable;

public class DefaultIdeaCompilerOutput implements IdeaCompilerOutput, Serializable {

    private boolean inheritOutputDirs;
    private File outputDir;
    private File testOutputDir;

    @Override
    public boolean getInheritOutputDirs() {
        return inheritOutputDirs;
    }

    public DefaultIdeaCompilerOutput setInheritOutputDirs(boolean inheritOutputDirs) {
        this.inheritOutputDirs = inheritOutputDirs;
        return this;
    }

    @Override
    public File getOutputDir() {
        return outputDir;
    }

    public DefaultIdeaCompilerOutput setOutputDir(File outputDir) {
        this.outputDir = outputDir;
        return this;
    }

    @Override
    public File getTestOutputDir() {
        return testOutputDir;
    }

    public DefaultIdeaCompilerOutput setTestOutputDir(File testOutputDir) {
        this.testOutputDir = testOutputDir;
        return this;
    }

    @Override
    public String toString() {
        return "IdeaCompilerOutput{"
                + "inheritOutputDirs=" + inheritOutputDirs
                + ", outputDir=" + outputDir
                + ", testOutputDir=" + testOutputDir
                + '}';
    }
}