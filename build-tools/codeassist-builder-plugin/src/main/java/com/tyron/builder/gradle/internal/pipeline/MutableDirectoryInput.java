package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.tyron.builder.api.transform.DirectoryInput;
import com.tyron.builder.api.transform.Status;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Mutable DirectoryInput implementation, allowing changes to the list of changed files.
 *
 * This is used to build instanceof of {@link ImmutableDirectoryInput} in steps.
 */
class MutableDirectoryInput extends QualifiedContentImpl {

    @NonNull
    private final Map<File, Status> changedFiles = Maps.newHashMap();

    private List<String> rootLocationSegments = null;

    MutableDirectoryInput(
            @NonNull String name,
            @NonNull File file,
            @NonNull Set<ContentType> contentTypes,
            @NonNull Set<? super Scope> scopes) {
        super(name, file, contentTypes, scopes);
    }

    @NonNull
    DirectoryInput asImmutable() {
        return new ImmutableDirectoryInput(
                getName(), getFile(), getContentTypes(), getScopes(), changedFiles);
    }

    /**
     * Process a changed file.
     *
     * If the file belongs to this folder then it is added to this folder's changed file map, and
     * the method returns true;
     *
     * @param file the changed file
     * @param fileSegments the changed file path segments for faster checks
     * @param status the status of the changed file.
     * @return true if the file belongs to this folder.
     */
    boolean processForChangedFile(
            @NonNull File file,
            @NonNull List<String> fileSegments,
            @NonNull Status status) {

        if (rootLocationSegments == null) {
            rootLocationSegments = Lists.newArrayList(
                    Splitter.on(File.separatorChar).split(getFile().getAbsolutePath()));
        }

        if (fileSegments.size() <= rootLocationSegments.size()) {
            return false;
        }

        // compare segments going backward as the leafs are more likely to be different.
        // We can ignore the segments of the file that are beyond the segments for the folder.
        for (int i = rootLocationSegments.size() - 1 ; i >= 0 ; i--) {
            if (!rootLocationSegments.get(i).equals(fileSegments.get(i))) {
                return false;
            }
        }

        // ok the file is part of the folder, add it to the changed list.
        addChangedFile(file, status);
        return true;
    }

    void addChangedFile(@NonNull File file, @NonNull Status status) {
        changedFiles.put(file, status);
    }
}
