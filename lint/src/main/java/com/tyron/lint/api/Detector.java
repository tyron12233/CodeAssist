package com.tyron.lint.api;

import org.openjdk.source.tree.Tree;

import java.util.List;

public abstract class Detector {

    public interface JavaScanner {

        JavaVoidVisitor getVisitor(JavaContext context);

        List<Class<? extends Tree>> getApplicableTypes();
    }

    public JavaVoidVisitor getVisitor(JavaContext context) {
        return null;
    }

    public List<Class<? extends Tree>> getApplicableTypes() {
        return null;
    }
}
