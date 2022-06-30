package com.tyron.builder.execution.taskpath;


import com.tyron.builder.api.internal.project.ProjectInternal;

public class ResolvedTaskPath {
    private final String prefix;
    private final String taskName;
    private final ProjectInternal project;
    private final boolean isQualified;

    public ResolvedTaskPath(String prefix, String taskName, ProjectInternal project) {
        this.prefix = prefix;
        this.taskName = taskName;
        this.project = project;
        this.isQualified = prefix.length() > 0;
    }

    public boolean isQualified() {
        return isQualified;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getTaskName() {
        return taskName;
    }

    /**
     * @return for qualified path it returns the path the task lives in.
     * For unqualified path it returns the project the task path was searched from.
     */
    public ProjectInternal getProject() {
        return project;
    }

    @Override
    public String toString() {
        return "ResolvedTaskPath{"
               + "prefix='" + prefix + '\''
               + ", taskName='" + taskName + '\''
               + ", project=" + project.getPath()
               + ", isQualified=" + isQualified
               + '}';
    }
}
