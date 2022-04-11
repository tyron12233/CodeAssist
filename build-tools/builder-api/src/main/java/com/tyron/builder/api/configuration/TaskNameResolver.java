package com.tyron.builder.api.configuration;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.tyron.builder.api.ProjectConfigurationException;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.execution.TaskSelectionResult;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.TaskContainer;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class TaskNameResolver {

    /**
     * Non-exhaustively searches for at least one task with the given name, by not evaluating projects before searching.
     */
    public boolean tryFindUnqualifiedTaskCheaply(String name, ProjectInternal project) {
        // don't evaluate children, see if we know it's without validating it
        for (BuildProject project1 : project.getAllprojects()) {
            for (Task task : project1.getTasks()) {
                if (name.equals(task.getName())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Finds tasks that will have exactly the given name, without necessarily creating or configuring the tasks. Returns null if no such match found.
     */
    @Nullable
    public TaskSelectionResult selectWithName(final String taskName, final ProjectInternal project, boolean includeSubProjects) {
        if (includeSubProjects) {
            Set<Task> tasks = Sets.newLinkedHashSet();
            new MultiProjectTaskSelectionResult(taskName, project, false).collectTasks(tasks);
            if (!tasks.isEmpty()) {
                return new FixedTaskSelectionResult(tasks);
            }
        } else {
            discoverTasks(project);
            if (hasTask(taskName, project)) {
                return new TaskSelectionResult() {
                    @Override
                    public void collectTasks(Collection<? super Task> tasks) {
                        tasks.add(getExistingTask(project, taskName));
                    }
                };
            }
        }

        return null;
    }

    /**
     * Finds the names of all tasks, without necessarily creating or configuring the tasks. Returns an empty map when none are found.
     */
    public Map<String, TaskSelectionResult> selectAll(ProjectInternal project, boolean includeSubProjects) {
        Map<String, TaskSelectionResult> selected = Maps.newLinkedHashMap();

        if (includeSubProjects) {
            Set<String> taskNames = Sets.newLinkedHashSet();
            collectTaskNames(project, taskNames);
            for (String taskName : taskNames) {
                selected.put(taskName, new MultiProjectTaskSelectionResult(taskName, project, true));
            }
        } else {
            discoverTasks(project);
            for (String taskName : getTaskNames(project)) {
                selected.put(taskName, new SingleProjectTaskSelectionResult(taskName, project.getTasks()));
            }
        }

        return selected;
    }

    private static void discoverTasks(ProjectInternal project) {
        try {
            project.getTasks().discoverTasks();
        } catch (Throwable e) {
            throw new ProjectConfigurationException(String.format("A problem occurred configuring %s.", project.getDisplayName()), e);
        }
    }

    private static Set<String> getTaskNames(ProjectInternal project) {
        return project.getTasks().stream().map(Task::getName)
                .collect(Collectors.toSet());
    }

    private static boolean hasTask(String taskName, ProjectInternal project) {
        return project.getTasks().stream().map(Task::getName)
                .anyMatch(taskName::equals);
    }

    private static TaskInternal getExistingTask(ProjectInternal project, String taskName) {
        try {
            return (TaskInternal) getByName(project.getTasks(), taskName)
                    .orElseThrow(RuntimeException::new);
        } catch (Throwable e) {
            throw new ProjectConfigurationException(String.format("A problem occurred configuring %s.", project.getDisplayName()), e);
        }
    }

    private void collectTaskNames(ProjectInternal project, Set<String> result) {
        discoverTasks(project);
        result.addAll(getTaskNames(project));
        for (BuildProject subProject : project.getChildProjects().values()) {
            collectTaskNames((ProjectInternal) subProject, result);
        }
    }

    private static class FixedTaskSelectionResult implements TaskSelectionResult {
        private final Collection<Task> tasks;

        FixedTaskSelectionResult(Collection<Task> tasks) {
            this.tasks = tasks;
        }

        @Override
        public void collectTasks(Collection<? super Task> tasks) {
            tasks.addAll(this.tasks);
        }
    }

    private static Optional<? extends Task> getByName(Collection<? extends Task> tasks, String taskName) {
        return tasks.stream().filter(it -> it.getName().equals(taskName))
                .findAny();
    }

    private static class SingleProjectTaskSelectionResult implements TaskSelectionResult {
        private final TaskContainer taskContainer;
        private final String taskName;

        SingleProjectTaskSelectionResult(String taskName, TaskContainer tasksContainer) {
            this.taskContainer = tasksContainer;
            this.taskName = taskName;
        }

        @Override
        public void collectTasks(Collection<? super Task> tasks) {
            getByName(taskContainer, taskName).ifPresent(tasks::add);
        }
    }

    private static class MultiProjectTaskSelectionResult implements TaskSelectionResult {
        private final ProjectInternal project;
        private final String taskName;
        private final boolean discovered;

        MultiProjectTaskSelectionResult(String taskName, ProjectInternal project, boolean discovered) {
            this.project = project;
            this.taskName = taskName;
            this.discovered = discovered;
        }

        @Override
        public void collectTasks(Collection<? super Task> tasks) {
            collect(project, tasks);
        }

        private void collect(ProjectInternal project, Collection<? super Task> tasks) {
            if (!discovered) {
                discoverTasks(project);
            }
            if (hasTask(taskName, project)) {
                TaskInternal task = getExistingTask(project, taskName);
                tasks.add(task);
                if (task.getImpliesSubProjects()) {
                    return;
                }
            }
            for (BuildProject subProject : project.getChildProjects().values()) {
                collect((ProjectInternal) subProject, tasks);
            }
        }
    }
}
