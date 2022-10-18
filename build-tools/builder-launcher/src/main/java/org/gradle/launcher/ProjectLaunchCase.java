package org.gradle.launcher;

import com.google.common.collect.ImmutableList;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.Project;
import org.gradle.internal.service.scopes.PluginServiceRegistry;
import com.tyron.common.TestUtil;

import java.io.File;
import java.util.List;

public abstract class ProjectLaunchCase {

    private final StartParameterInternal startParameter = new StartParameterInternal();
    private final ProjectLauncher launcher;

    public ProjectLaunchCase() {
        startParameter.setProjectDir(getRootDirectory());
        startParameter.setTaskNames(getTasks());

        launcher = new ProjectLauncher(startParameter);
    }

    public void execute() {
        launcher.execute();
    }

    protected List<PluginServiceRegistry> getPluginServiceRegistries() {
        return ImmutableList.of();
    }

    /**
     * Configures the root project and any other project that is registered.
     * @param project The project to configure
     */
    public abstract void configure(Project project);

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
