package com.tyron.builder.api.internal.tasks.compile.incremental.recomp;

import java.util.Set;

public interface SourceFileClassNameConverter {
    /**
     * Returns the classes that were compiled from this source file or an empty set if unknown.
     */
    Set<String> getClassNames(String sourceFileRelativePath);

    /**
     * Returns the source files that this class was compiled from.
     * Can be multiple files when the same class declaration was made in several files.
     * This happens e.g. during "copy class" refactorings in IntelliJ.
     * Empty if the source for this class could not be determined.
     */
    Set<String> getRelativeSourcePaths(String className);
}
