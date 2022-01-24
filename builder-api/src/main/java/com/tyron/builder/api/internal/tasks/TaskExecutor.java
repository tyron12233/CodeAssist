package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.tasks.TaskDependency;

import java.util.List;
import java.util.Set;

public class TaskExecutor {

    public void runTask(Task task) {
        runActions(task);

        Set<? extends Task> dependencies = task.getTaskDependencies().getDependencies(task);
        for (Task dependency : dependencies) {
            if (checkCircularDependency(task, dependency)) {
                throw new CircularDependencyException();
            }

            runActions(dependency);
        }

        for (Task dependency : dependencies) {

        }
    }

    private void runTaskInternal() {

    }

    private boolean checkCircularDependency(Task task, Task otherTask) {
        if (task.equals(otherTask)) {
            return true;
        }
        if (otherTask.getTaskDependencies().getDependencies(otherTask).contains(task)) {
            return true;
        }
        return false;
    }

    private void runActions(Task task) {
        List<Action<? super Task>> actions = task.getActions();
        for (Action<? super Task> action : actions) {
            action.execute(task);
        }
    }
}
