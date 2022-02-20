package com.tyron.completion.xml.repository.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;
import com.tyron.completion.xml.repository.api.ResourceValueImpl;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import kotlin.io.FilesKt;

/**
 * A parser used by temporary implementations to generate R.java fields
 */
public class TemporaryParser implements ResourceParser {

    private final ResourceType mType;

    public TemporaryParser(@NonNull ResourceType type) {
        mType = type;
    }

    @Override
    public List<ResourceValue> parse(@NonNull File file,
                                     @NonNull String contents,
                                     @NonNull ResourceNamespace namespace,
                                     @Nullable String libraryName) throws IOException {
        // temporary parser so drawables would get generated to R.java
        ResourceReference reference = new ResourceReference(namespace, mType,
                                                            FilesKt.getNameWithoutExtension(file));
        ResourceValue value = new ResourceValueImpl(reference, null, libraryName);
        return Collections.singletonList(value);
    }
}
