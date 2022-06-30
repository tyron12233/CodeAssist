package com.tyron.builder.api.internal.project.taskfactory;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.changedetection.changes.ChangesOnlyIncrementalTaskInputs;
import com.tyron.builder.api.internal.changedetection.changes.RebuildIncrementalTaskInputs;
import com.tyron.builder.internal.execution.history.changes.InputChangesInternal;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.reflect.JavaMethod;
import com.tyron.builder.api.tasks.incremental.IncrementalTaskInputs;
import com.tyron.builder.api.tasks.incremental.InputFileDetails;

import java.lang.reflect.Method;

public class IncrementalTaskInputsTaskAction extends AbstractIncrementalTaskAction {
    private final Instantiator instantiator;

    public IncrementalTaskInputsTaskAction(Instantiator instantiator, Class<? extends Task> type, Method method) {
        super(type, method);
        this.instantiator = instantiator;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void doExecute(Task task, String methodName) {
        InputChangesInternal inputChanges = getInputChanges();

        Iterable<InputFileDetails> allFileChanges = inputChanges.getAllFileChanges();
        IncrementalTaskInputs incrementalTaskInputs = inputChanges.isIncremental()
                ? createIncrementalInputs(allFileChanges)
                : createRebuildInputs(allFileChanges);

        invokeMethod(task, methodName, incrementalTaskInputs);
    }

    protected void invokeMethod(Task task,
                           String methodName,
                           IncrementalTaskInputs incrementalTaskInputs) {
        JavaMethod.of(task, Object.class, methodName, IncrementalTaskInputs.class).invoke(task,
                incrementalTaskInputs);
    }


    private ChangesOnlyIncrementalTaskInputs createIncrementalInputs(Iterable<InputFileDetails> allFileChanges) {
        return instantiator.newInstance(ChangesOnlyIncrementalTaskInputs.class, allFileChanges);
    }

    private RebuildIncrementalTaskInputs createRebuildInputs(Iterable<InputFileDetails> allFileChanges) {
        return instantiator.newInstance(RebuildIncrementalTaskInputs.class, allFileChanges);
    }
}