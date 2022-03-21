package com.tyron.builder.api.tasks;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;

import java.io.File;
import java.util.Set;

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
}
