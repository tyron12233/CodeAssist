package com.tyron.completion.xml.repository;

import androidx.annotation.NonNull;

import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;

import java.io.IOException;

public interface Repository {

    @NonNull
    ResourceNamespace getNamespace();

    ResourceValue getValue(ResourceReference reference);

    default ResourceValue getValue(String name, boolean resolveRefs) {
        return getValue(getNamespace(), name, resolveRefs);
    }

    ResourceValue getValue(ResourceNamespace namespace, String name, boolean resolveRefs);

    void initialize() throws IOException;
}
