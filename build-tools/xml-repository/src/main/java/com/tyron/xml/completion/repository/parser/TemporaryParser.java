package com.tyron.xml.completion.repository.parser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceReference;
import com.tyron.xml.completion.repository.api.ResourceValue;
import com.tyron.xml.completion.repository.api.ResourceValueImpl;

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

    public TemporaryParser(@NotNull ResourceType type) {
        mType = type;
    }

    @Override
    public List<ResourceValue> parse(@NotNull File file,
                                     @Nullable String contents,
                                     @NotNull ResourceNamespace namespace,
                                     @Nullable String libraryName) throws IOException {
        // temporary parser so drawables would get generated to R.java
        ResourceReference reference = new ResourceReference(namespace, mType,
                                                            FilesKt.getNameWithoutExtension(file));
        ResourceValue value = new ResourceValueImpl(reference, null, libraryName);
        return Collections.singletonList(value);
    }
}
