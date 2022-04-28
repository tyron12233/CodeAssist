package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.collections.MinimalFileSet;
import com.tyron.builder.api.internal.tasks.properties.LifecycleAwareValue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CalculatedTaskInputFileCollection extends AbstractFileCollection implements LifecycleAwareValue {
    private final String taskPath;
    private MinimalFileSet calculatedFiles;
    private List<LifecycleAwareValue> targets;
    private Set<File> cachedFiles;
    private boolean taskIsExecuting;

    public CalculatedTaskInputFileCollection(String taskPath, MinimalFileSet calculatedFiles, Object[] inputs) {
        this.taskPath = taskPath;
        this.calculatedFiles = calculatedFiles;
        targets = new ArrayList<>(1 + inputs.length);
        for (Object input : inputs) {
            if (input instanceof LifecycleAwareValue) {
                targets.add((LifecycleAwareValue) input);
            }
        }
        if (calculatedFiles instanceof LifecycleAwareValue) {
            targets.add((LifecycleAwareValue) calculatedFiles);
        }
    }

    @Override
    public String getDisplayName() {
        return calculatedFiles.getDisplayName();
    }

    @Override
    public Set<File> getFiles() {
        if (!taskIsExecuting) {
            throw new IllegalStateException("Can only query " + calculatedFiles.getDisplayName() + " while task " + taskPath + " is running");
        }
        if (cachedFiles == null) {
            cachedFiles = calculatedFiles.getFiles();
        }
        return cachedFiles;
    }

    @Override
    public void prepareValue() {
        taskIsExecuting = true;
        for (LifecycleAwareValue target : targets) {
            target.prepareValue();
        }
    }

    @Override
    public void cleanupValue() {
        taskIsExecuting = false;
        cachedFiles = null;
        for (LifecycleAwareValue target : targets) {
            target.cleanupValue();
        }
        targets = null;
        // Discard the calculated files collection too, but need to retain the display name for it
    }
}
