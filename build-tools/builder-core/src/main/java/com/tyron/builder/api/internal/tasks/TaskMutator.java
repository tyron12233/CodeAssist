package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.internal.UncheckedException;

import java.beans.PropertyChangeEvent;
import java.util.concurrent.Callable;

public class TaskMutator {
    private final TaskInternal task;

    public TaskMutator(TaskInternal task) {
        this.task = task;
    }

    public void mutate(String method, Runnable action) {
        if (!task.getState().isConfigurable()) {
            throw new IllegalStateException(format(method));
        }
        action.run();
    }

    public <T> T mutate(String method, Callable<T> action) {
        if (!task.getState().isConfigurable()) {
            throw new IllegalStateException(format(method));
        }
        try {
            return action.call();
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    public void assertMutable(String listname, PropertyChangeEvent evt) {
        if (task.getState().isConfigurable()) {
            return;
        }

        String method = null;
//        if (evt instanceof ObservableList.ElementEvent) {
//            switch (((ObservableList.ElementEvent) evt).getChangeType()) {
//                case ADDED:
//                    method = String.format("%s.%s", listname, "add()");
//                    break;
//                case UPDATED:
//                    method = String.format("%s.%s", listname, "set(int, Object)");
//                    break;
//                case REMOVED:
//                    method = String.format("%s.%s", listname, "remove()");
//                    break;
//                case CLEARED:
//                    method = String.format("%s.%s", listname, "clear()");
//                    break;
//                case MULTI_ADD:
//                    method = String.format("%s.%s", listname, "addAll()");
//                    break;
//                case MULTI_REMOVE:
//                    method = String.format("%s.%s", listname, "removeAll()");
//                    break;
//            }
//        }
        if (method == null) {
            return;
        }

        throw new IllegalStateException(format(method));
    }

    private String format(String method) {
        return String.format("Cannot call %s on %s after task has started execution.", method, task);
    }
}