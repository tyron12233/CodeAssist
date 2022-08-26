package org.gradle.plugins.ide.internal.tooling.idea;

import java.io.File;
import java.io.Serializable;

public class DefaultIdeaSourceDirectory implements Serializable {

    private File directory;

    private boolean generated;

    public File getDirectory() {
        return directory;
    }

    public boolean isGenerated() {
        return generated;
    }

    public DefaultIdeaSourceDirectory setDirectory(File directory) {
        this.directory = directory;
        return this;
    }

    public DefaultIdeaSourceDirectory setGenerated(boolean generated) {
        this.generated = generated;
        return this;
    }

    @Override
    public String toString() {
        return "DefaultIdeaSourceDirectory{"
                + "directory=" + directory
                + ", generated=" + generated
                + '}';
    }
}