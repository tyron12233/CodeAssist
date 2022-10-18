package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.api.transform.QualifiedContent;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.Serializable;
import java.util.Set;

/** Basic implementation of {@link QualifiedContent}. */
@Immutable
class QualifiedContentImpl implements QualifiedContent, Serializable {

    @NonNull
    private final String name;
    @NonNull
    private final File file;
    @NonNull
    private final Set<ContentType> contentTypes;
    @NonNull
    private final Set<? super Scope> scopes;

    protected QualifiedContentImpl(
            @NonNull String name,
            @NonNull File file,
            @NonNull Set<ContentType> contentTypes,
            @NonNull Set<? super Scope> scopes) {
        this.name = name;
        this.file = file;
        this.contentTypes = ImmutableSet.copyOf(contentTypes);
        this.scopes = ImmutableSet.copyOf(scopes);
    }

    protected QualifiedContentImpl(@NonNull QualifiedContent qualifiedContent) {
        this.name = qualifiedContent.getName();
        this.file = qualifiedContent.getFile();
        this.contentTypes = qualifiedContent.getContentTypes();
        this.scopes = qualifiedContent.getScopes();
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    @NonNull
    public File getFile() {
        return file;
    }

    @NonNull
    @Override
    public Set<ContentType> getContentTypes() {
        return contentTypes;
    }

    @NonNull
    @Override
    public Set<? super Scope> getScopes() {
        return scopes;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("file", file)
                .add("contentTypes", contentTypes)
                .add("scopes", scopes)
                .toString();
    }
}
