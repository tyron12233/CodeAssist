package com.tyron.builder.api.internal;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.tasks.TaskOutputs;

import java.io.File;
import java.util.Set;
import java.util.function.Predicate;

public interface TaskOutputsInternal extends TaskOutputs {

    /**
     * Calls the corresponding visitor methods for all outputs added via the runtime API.
     */
    void visitRegisteredProperties(PropertyVisitor visitor);

    void setPreviousOutputFiles(FileCollection previousOutputFiles);

    /**
     * Returns the output files and directories recorded during the previous execution of the task.
     */
    Set<File> getPreviousOutputFiles();

    Predicate<? super TaskInternal> getUpToDateSpec();
}
