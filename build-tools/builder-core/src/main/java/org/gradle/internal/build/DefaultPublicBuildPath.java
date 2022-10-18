package org.gradle.internal.build;

import org.gradle.util.Path;

public class DefaultPublicBuildPath implements PublicBuildPath {

    private final Path path;

    public DefaultPublicBuildPath(Path path) {
        this.path = path;
    }

    @Override
    public Path getBuildPath() {
        return path;
    }

}