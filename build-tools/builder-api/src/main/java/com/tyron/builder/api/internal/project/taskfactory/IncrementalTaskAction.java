package com.tyron.builder.api.internal.project.taskfactory;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.changedetection.changes.ChangesOnlyIncrementalTaskInputs;
import com.tyron.builder.api.internal.changedetection.changes.RebuildIncrementalTaskInputs;
import com.tyron.builder.api.internal.execution.history.InputChangesInternal;
import com.tyron.builder.api.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.api.internal.reflect.DirectInstantiator;
import com.tyron.builder.api.internal.snapshot.impl.ImplementationSnapshot;
import com.tyron.builder.api.internal.tasks.InputChangesAwareTaskAction;
import com.tyron.builder.api.tasks.incremental.IncrementalTaskInputs;
import com.tyron.builder.api.tasks.incremental.InputFileDetails;
import com.tyron.builder.api.work.InputChanges;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

@SuppressWarnings("deprecation")
public abstract class IncrementalTaskAction implements InputChangesAwareTaskAction {

    private InputChangesInternal inputChangesInternal;

    @Override
    public void execute(Task task) {
        execute(getInputChanges());
    }

    public abstract void execute(@Nullable InputChanges inputs);

    @Override
    public String getDisplayName() {
        return "Incremental actions";
    }

    @Override
    public ImplementationSnapshot getActionImplementation(ClassLoaderHierarchyHasher hasher) {
        return ImplementationSnapshot.of("IncrementalTaskAction", hasher.getClassLoaderHash(getClass().getClassLoader()));
    }

    @Override
    public void setInputChanges(InputChangesInternal inputChanges) {
        this.inputChangesInternal = inputChanges;
    }

    @Override
    public void clearInputChanges() {
        this.inputChangesInternal = null;
    }

    protected InputChangesInternal getInputChanges() {
        return inputChangesInternal;
    }

    private ChangesOnlyIncrementalTaskInputs createIncrementalInputs(Iterable<InputFileDetails> allFileChanges) {
        return DirectInstantiator.instantiate(ChangesOnlyIncrementalTaskInputs.class, allFileChanges);
    }

    private RebuildIncrementalTaskInputs createRebuildInputs(Iterable<InputFileDetails> allFileChanges) {
        return DirectInstantiator.instantiate(RebuildIncrementalTaskInputs.class, allFileChanges);
    }
}
