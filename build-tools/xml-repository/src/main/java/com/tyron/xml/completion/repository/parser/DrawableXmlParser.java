package com.tyron.xml.completion.repository.parser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceValue;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class DrawableXmlParser implements ResourceParser {
    @Override
    public List<ResourceValue> parse(@NotNull File file,
                                     @Nullable String contents,
                                     @NotNull ResourceNamespace namespace,
                                     @Nullable String libraryName) throws IOException {
        return null;
    }
}
