package com.tyron.lint.api;

import org.openjdk.source.tree.Tree;

import java.util.List;

public abstract class Detector {

    public interface JavaScanner {

        JavaVoidVisitor getVisitor();

        List<Class<? extends Tree>> getApplicableTypes();
    }

    public JavaVoidVisitor getVisitor() {
        return null;
    }

    public List<Class<? extends Tree>> getApplicableTypes() {
        return null;
    }
}
