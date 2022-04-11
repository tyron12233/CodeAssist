package com.tyron.builder.launcher;

import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.common.TestUtil;

import java.io.File;
import java.util.List;

public abstract class ProjectLaunchCase {

    private final StartParameterInternal startParameter = new StartParameterInternal();
    private final ProjectLauncher launcher = new ProjectLauncher(startParameter) {
        @Override
        public void configure(BuildProject project) {
            ProjectLaunchCase.this.configure(project);
        }
    };

    public ProjectLaunchCase() {
        startParameter.setProjectDir(getRootDirectory());
        startParameter.setTaskNames(getTasks());
    }

    public void execute() {
        launcher.execute();
    }

    /**
     * Configures the root project and any other project that is registered.
     * @param project The project to configure
     */
    public abstract void configure(BuildProject project);

    /**
     * @return The root directory of the project
     */
    protected File getRootDirectory() {
        return new File(TestUtil.getResourcesDirectory(), getRootProjectName());
    }

    protected String getRootProjectName() {
        return "TestProject";
    }

    /**
     * @return The list of task names to execute
     */
    public abstract List<String> getTasks();
}
