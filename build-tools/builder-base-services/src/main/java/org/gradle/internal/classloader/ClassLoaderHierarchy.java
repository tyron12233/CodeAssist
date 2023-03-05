package org.gradle.internal.classloader;

public interface ClassLoaderHierarchy {
    void visit(ClassLoaderVisitor visitor);
}