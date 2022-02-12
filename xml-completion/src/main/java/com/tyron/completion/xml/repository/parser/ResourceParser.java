package com.tyron.completion.xml.repository.parser;

import androidx.annotation.NonNull;

import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceValue;

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface ResourceParser {

    List<ResourceValue> parse(@NonNull File file, ResourceNamespace namespace) throws IOException;
}
