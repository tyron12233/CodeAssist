package com.tyron.completion.xml.repository.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceValue;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class DrawableXmlParser implements ResourceParser {
    @Override
    public List<ResourceValue> parse(@NonNull File file,
                                     @NonNull String contents,
                                     @NonNull ResourceNamespace namespace,
                                     @Nullable String libraryName) throws IOException {
        return null;
    }
}
