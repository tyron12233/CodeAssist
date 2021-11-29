package com.tyron.builder.project.indexer;

import com.tyron.builder.project.api.Project;

import java.io.File;
import java.util.List;

/**
 * Used by {@link Project} to query different kinds of files
 */
public interface Indexer {
    List<File> index(Project project);
}
