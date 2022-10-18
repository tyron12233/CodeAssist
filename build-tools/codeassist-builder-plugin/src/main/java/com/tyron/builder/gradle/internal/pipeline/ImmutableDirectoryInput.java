package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.api.transform.DirectoryInput;
import com.tyron.builder.api.transform.Status;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Immutable version of {@link DirectoryInput}.
 */
@Immutable
class ImmutableDirectoryInput extends QualifiedContentImpl implements DirectoryInput {

    @NonNull
    private final Map<File, Status> changedFiles;

    ImmutableDirectoryInput(
            @NonNull String name,
            @NonNull File file,
            @NonNull Set<ContentType> contentTypes,
            @NonNull Set<? super Scope> scopes) {
        super(name, file, contentTypes, scopes);
        this.changedFiles = ImmutableMap.of();
    }

    protected ImmutableDirectoryInput(
            @NonNull String name,
            @NonNull File file,
            @NonNull Set<ContentType> contentTypes,
            @NonNull Set<? super Scope> scopes,
            @NonNull Map<File, Status> changedFiles) {
        super(name, file, contentTypes, scopes);
        this.changedFiles = ImmutableMap.copyOf(changedFiles);
    }

    @NonNull
    @Override
    public Map<File, Status> getChangedFiles() {
        return changedFiles;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("file", getFile())
                .add("contentTypes", Joiner.on(',').join(getContentTypes()))
                .add("scopes", Joiner.on(',').join(getScopes()))
                .add("changedFiles", changedFiles)
                .toString();
    }
}
