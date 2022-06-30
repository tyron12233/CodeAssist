package com.tyron.builder.internal.classloader;

public interface ClassLoaderHierarchy {
    void visit(ClassLoaderVisitor visitor);
}