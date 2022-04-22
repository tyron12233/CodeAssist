package com.tyron.builder.execution.taskpath;

import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.BuildProject;

public class TaskPathResolver {

    private final ProjectFinderByTaskPath projectFinder;

    TaskPathResolver(ProjectFinderByTaskPath projectFinder) {
        this.projectFinder = projectFinder;
    }

    public TaskPathResolver() {
        this(new ProjectFinderByTaskPath());
    }

    /**
     * @param path the task path, e.g. 'someTask', 'sT', ':sT', ':foo:bar:sT'
     * @param startFrom the starting project the task should be found recursively
     * @return resolved task path
     */
    public ResolvedTaskPath resolvePath(String path, ProjectInternal startFrom) {
        ProjectInternal project;
        String taskName; //eg. 'someTask' or 'sT'
        String prefix; //eg. '', ':' or ':foo:bar'

        if (path.contains(BuildProject.PATH_SEPARATOR)) {
            int idx = path.lastIndexOf(BuildProject.PATH_SEPARATOR);
            taskName = path.substring(idx + 1);
            prefix = path.substring(0, idx+1);
            String projectPath = BuildProject.PATH_SEPARATOR.equals(prefix) ? prefix : path.substring(0, idx);
            project = projectFinder.findProject(projectPath, startFrom);
        } else {
            project = startFrom;
            taskName = path;
            prefix = "";
        }
        return new ResolvedTaskPath(prefix, taskName, project);
    }
}