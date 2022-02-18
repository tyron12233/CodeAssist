package com.tyron.completion.xml.repository.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceValue;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public interface ResourceParser {

    default List<ResourceValue> parse(@NonNull File file,
                              @NonNull ResourceNamespace namespace,
                              @Nullable String libraryName) throws IOException {
        String contents = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        return parse(file, contents, namespace, libraryName);
    }

    List<ResourceValue> parse(@NonNull File file,
                              @NonNull String contents,
                              @NonNull ResourceNamespace namespace,
                              @Nullable String libraryName) throws IOException;
}
