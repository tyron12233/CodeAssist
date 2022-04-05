package com.tyron.builder.api.internal.hash;

public interface ClassLoaderHierarchy {
    void visit(ClassLoaderVisitor visitor);
}