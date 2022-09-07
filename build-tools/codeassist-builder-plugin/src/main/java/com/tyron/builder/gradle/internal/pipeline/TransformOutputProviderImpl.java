package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.tyron.builder.api.transform.Format;
import com.tyron.builder.api.transform.QualifiedContent;
import com.tyron.builder.api.transform.TransformOutputProvider;
import com.android.utils.FileUtils;
import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * Implementation of {@link TransformOutputProvider} passed to the transforms.
 */
class TransformOutputProviderImpl implements TransformOutputProvider {

    @NonNull private final IntermediateFolderUtils folderUtils;

    TransformOutputProviderImpl(@NonNull IntermediateFolderUtils folderUtils) {
        this.folderUtils = folderUtils;
    }

    @Override
    public void deleteAll() throws IOException {
        FileUtils.cleanOutputDir(folderUtils.getRootFolder());
    }

    @NonNull
    @Override
    public File getContentLocation(
            @NonNull String name,
            @NonNull Set<QualifiedContent.ContentType> types,
            @NonNull Set<? super QualifiedContent.Scope> scopes,
            @NonNull Format format) {
        return folderUtils.getContentLocation(name, types, scopes, format);
    }
}
