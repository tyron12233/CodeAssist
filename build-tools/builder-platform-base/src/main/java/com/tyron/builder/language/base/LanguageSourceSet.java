package com.tyron.builder.language.base;

import com.tyron.builder.api.BuildableComponentSpec;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.SourceDirectorySet;
import com.tyron.builder.internal.HasInternalProtocol;

import javax.annotation.Nullable;

/**
 * A set of sources for a programming language.
 */
@Incubating
@HasInternalProtocol
public interface LanguageSourceSet extends BuildableComponentSpec {

    // TODO: do we want to keep using SourceDirectorySet in the new API?
    // would feel more natural if dirs could be added directly to LanguageSourceSet
    // could also think about extending SourceDirectorySet

    /**
     * The source files.
     */
    SourceDirectorySet getSource();

    void generatedBy(Task generatorTask);

    @Nullable
    String getParentName();
}
