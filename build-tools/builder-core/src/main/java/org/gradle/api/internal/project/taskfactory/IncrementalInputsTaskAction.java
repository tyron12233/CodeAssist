package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Task;
import org.gradle.internal.reflect.JavaMethod;
import org.gradle.work.InputChanges;

import java.lang.reflect.Method;

public class IncrementalInputsTaskAction extends AbstractIncrementalTaskAction {
    public IncrementalInputsTaskAction(Class<? extends Task> type, Method method) {
        super(type, method);
    }

    @Override
    protected void doExecute(Task task, String methodName) {
        JavaMethod.of(task, Object.class, methodName, InputChanges.class).invoke(task, getInputChanges());
    }
}