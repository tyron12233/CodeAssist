package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Task;
import org.gradle.api.internal.changedetection.changes.ChangesOnlyIncrementalTaskInputs;
import org.gradle.api.internal.changedetection.changes.RebuildIncrementalTaskInputs;
import org.gradle.internal.execution.history.changes.InputChangesInternal;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.reflect.JavaMethod;
import org.gradle.api.tasks.incremental.IncrementalTaskInputs;
import org.gradle.api.tasks.incremental.InputFileDetails;

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