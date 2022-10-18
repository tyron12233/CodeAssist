package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Task;
import org.gradle.api.internal.changedetection.changes.ChangesOnlyIncrementalTaskInputs;
import org.gradle.api.internal.changedetection.changes.RebuildIncrementalTaskInputs;
import org.gradle.internal.execution.history.changes.InputChangesInternal;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.reflect.DirectInstantiator;
import org.gradle.internal.snapshot.impl.ImplementationSnapshot;
import org.gradle.api.internal.tasks.InputChangesAwareTaskAction;
import org.gradle.api.tasks.incremental.InputFileDetails;
import org.gradle.work.InputChanges;

import org.jetbrains.annotations.Nullable;

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
