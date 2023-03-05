package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Task;
import org.gradle.internal.execution.history.changes.InputChangesInternal;
import org.gradle.api.internal.tasks.InputChangesAwareTaskAction;

import java.lang.reflect.Method;

public abstract class AbstractIncrementalTaskAction extends StandardTaskAction implements InputChangesAwareTaskAction {
    private InputChangesInternal inputChanges;

    public AbstractIncrementalTaskAction(Class<? extends Task> type, Method method) {
        super(type, method);
    }

    @Override
    public void setInputChanges(InputChangesInternal inputChanges) {
        this.inputChanges = inputChanges;
    }

    @Override
    public void clearInputChanges() {
        this.inputChanges = null;
    }

    protected InputChangesInternal getInputChanges() {
        return inputChanges;
    }
}