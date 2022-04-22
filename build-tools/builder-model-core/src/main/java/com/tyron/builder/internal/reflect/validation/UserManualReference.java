package com.tyron.builder.internal.reflect.validation;

import com.tyron.builder.api.internal.DocumentationRegistry;

public class UserManualReference {
    private final DocumentationRegistry documentationRegistry;
    private final String id;
    private final String section;

    UserManualReference(DocumentationRegistry documentationRegistry, String id, String section) {
        this.documentationRegistry = documentationRegistry;
        this.id = id;
        this.section = section;
    }

    public String getId() {
        return id;
    }

    public String getSection() {
        return section;
    }

    public String toDocumentationLink() {
        return documentationRegistry.getDocumentationFor(id, section);
    }
}
