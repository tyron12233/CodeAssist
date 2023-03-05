package org.gradle.api.internal;

import org.gradle.api.NonNullApi;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.tasks.execution.SelfDescribingSpec;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.specs.AndSpec;
import org.gradle.api.tasks.TaskOutputs;

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
