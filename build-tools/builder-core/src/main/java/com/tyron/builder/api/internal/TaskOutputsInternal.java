package com.tyron.builder.api.internal;

import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.tasks.execution.SelfDescribingSpec;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.specs.AndSpec;
import com.tyron.builder.api.tasks.TaskOutputs;

import java.io.File;
import java.util.List;
import java.util.Set;

@NonNullApi
public interface TaskOutputsInternal extends TaskOutputs {

    /**
     * Calls the corresponding visitor methods for all outputs added via the runtime API.
     */
    void visitRegisteredProperties(PropertyVisitor visitor);

    AndSpec<? super TaskInternal> getUpToDateSpec();

    void setPreviousOutputFiles(FileCollection previousOutputFiles);

    /**
     * Returns the output files and directories recorded during the previous execution of the task.
     */
    Set<File> getPreviousOutputFiles();

    List<SelfDescribingSpec<TaskInternal>> getCacheIfSpecs();

    List<SelfDescribingSpec<TaskInternal>> getDoNotCacheIfSpecs();

}
