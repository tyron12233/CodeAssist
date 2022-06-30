package com.tyron.builder.api.internal.project.taskfactory;

import com.tyron.builder.api.Task;
import com.tyron.builder.internal.reflect.JavaMethod;
import com.tyron.builder.work.InputChanges;

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