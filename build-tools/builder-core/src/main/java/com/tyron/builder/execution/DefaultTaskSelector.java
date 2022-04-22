package com.tyron.builder.execution;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.initialization.IncludedBuild;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.execution.taskpath.ResolvedTaskPath;
import com.tyron.builder.execution.taskpath.TaskPathResolver;
import com.tyron.builder.util.NameMatcher;

import org.slf4j.Logger;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

public class DefaultTaskSelector extends TaskSelector {
    private static final Logger LOGGER = Logging.getLogger(DefaultTaskSelector.class);

    private final TaskNameResolver taskNameResolver;
    private final GradleInternal gradle;
    private final ProjectConfigurer configurer;
    private final TaskPathResolver taskPathResolver = new TaskPathResolver();

    public DefaultTaskSelector(GradleInternal gradle, TaskNameResolver taskNameResolver, ProjectConfigurer configurer) {
        this.taskNameResolver = taskNameResolver;
        this.gradle = gradle;
        this.configurer = configurer;
    }

    public TaskSelection getSelection(String path) {
        return getSelection(path, gradle.getDefaultProject());
    }

    public Predicate<Task> getFilter(String path) {
        final ResolvedTaskPath taskPath = taskPathResolver.resolvePath(path, gradle.getDefaultProject());
        if (!taskPath.isQualified()) {
            ProjectInternal targetProject = taskPath.getProject();
            configurer.configure(targetProject);
            if (taskNameResolver.tryFindUnqualifiedTaskCheaply(taskPath.getTaskName(), taskPath.getProject())) {
                // An exact match in the target project - can just filter tasks by path to avoid configuring sub-projects at this point
                return new TaskPathSpec(targetProject, taskPath.getTaskName());
            }
        }

        final Set<Task> selectedTasks = getSelection(path, gradle.getDefaultProject()).getTasks();
        return new Predicate<Task>() {
            @Override
            public boolean test(Task element) {
                return !selectedTasks.contains(element);
            }
        };
    }

    public TaskSelection getSelection(@Nullable String projectPath, @Nullable File root, String path) {
        if (root != null) {
            ensureNotFromIncludedBuild(root);
        }

        ProjectInternal project = projectPath != null
                ? gradle.getRootProject().findProject(projectPath)
                : gradle.getDefaultProject();
        return getSelection(path, project);
    }

    private void ensureNotFromIncludedBuild(File root) {
        Set<File> includedRoots = new HashSet<File>();
        for (IncludedBuild includedBuild : gradle.getIncludedBuilds()) {
            includedRoots.add(includedBuild.getProjectDir());
        }
        if (includedRoots.contains(root)) {
            throw new TaskSelectionException("Can't launch tasks from included builds");
        }
    }

    private TaskSelection getSelection(String path, ProjectInternal project) {
        ResolvedTaskPath taskPath = taskPathResolver.resolvePath(path, project);
        ProjectInternal targetProject = taskPath.getProject();
        if (taskPath.isQualified()) {
            configurer.configure(targetProject);
        } else {
            configurer.configureHierarchy(targetProject);
        }

        TaskSelectionResult tasks = taskNameResolver.selectWithName(taskPath.getTaskName(), taskPath.getProject(), !taskPath.isQualified());
        if (tasks != null) {
            LOGGER.info("Task name matched '{}'", taskPath.getTaskName());
            return new TaskSelection(taskPath.getProject().getPath(), path, tasks);
        } else {
            Map<String, TaskSelectionResult> tasksByName = taskNameResolver.selectAll(taskPath.getProject(), !taskPath.isQualified());
            NameMatcher matcher = new NameMatcher();
            String actualName = matcher.find(taskPath.getTaskName(), tasksByName.keySet());

            if (actualName != null) {
                LOGGER.info("Abbreviated task name '{}' matched '{}'", taskPath.getTaskName(), actualName);
                return new TaskSelection(taskPath.getProject().getPath(), taskPath.getPrefix() + actualName, tasksByName.get(actualName));
            }

            throw new TaskSelectionException(matcher.formatErrorMessage("task", taskPath.getProject()));
        }
    }

    private static class TaskPathSpec implements Predicate<Task> {
        private final ProjectInternal targetProject;
        private final String taskName;

        public TaskPathSpec(ProjectInternal targetProject, String taskName) {
            this.targetProject = targetProject;
            this.taskName = taskName;
        }

        @Override
        public boolean test(Task element) {
            if (!element.getName().equals(taskName)) {
                return true;
            }
            for (BuildProject current = element.getProject(); current != null; current = current.getParent()) {
                if (current.equals(targetProject)) {
                    return false;
                }
            }
            return true;
        }
    }
}
