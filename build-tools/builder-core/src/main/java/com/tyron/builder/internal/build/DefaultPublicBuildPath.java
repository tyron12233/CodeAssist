package com.tyron.builder.internal.build;

import com.tyron.builder.util.Path;

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