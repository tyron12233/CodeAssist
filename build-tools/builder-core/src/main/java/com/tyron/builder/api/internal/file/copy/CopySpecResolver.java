package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.DuplicatesStrategy;
import com.tyron.builder.api.file.FileCopyDetails;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.RelativePath;

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

    List<Predicate<FileTreeElement>> getAllIncludeSpecs();

    List<Predicate<FileTreeElement>> getAllExcludeSpecs();

    DuplicatesStrategy getDuplicatesStrategy();

    boolean isDefaultDuplicateStrategy();

    void walk(Action<? super CopySpecResolver> action);


}