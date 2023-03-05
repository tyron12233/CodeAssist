package org.gradle.api.internal.file.copy;

import org.gradle.api.Action;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.RelativePath;

import org.gradle.api.specs.Spec;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public interface CopySpecResolver {

    boolean isCaseSensitive();
    @Nullable
    Integer getFileMode();
    @Nullable
    Integer getDirMode();
    boolean getIncludeEmptyDirs();
    String getFilteringCharset();

    RelativePath getDestPath();

    /**
     * Returns the source files of this copy spec.
     */
    FileTree getSource();

    /**
     * Returns the source files of this copy spec and all of its children.
     */
    FileTree getAllSource();

    Collection<? extends Action<? super FileCopyDetails>> getAllCopyActions();

    List<String> getAllIncludes();

    List<String> getAllExcludes();

    List<Spec<FileTreeElement>> getAllIncludeSpecs();

    List<Spec<FileTreeElement>> getAllExcludeSpecs();

    DuplicatesStrategy getDuplicatesStrategy();

    boolean isDefaultDuplicateStrategy();

    void walk(Action<? super CopySpecResolver> action);


}