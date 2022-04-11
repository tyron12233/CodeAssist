package com.tyron.builder.launcher;

import com.tyron.builder.api.StartParameter;

public abstract class ProjectLauncher {

    private final StartParameter startParameter;

    public ProjectLauncher(StartParameter startParameter) {
        this.startParameter = startParameter;
    }

    public void execute() {

    }
}
