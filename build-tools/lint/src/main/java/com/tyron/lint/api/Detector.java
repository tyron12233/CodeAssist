package com.tyron.lint.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;

import java.util.List;

public abstract class Detector {

    public interface JavaScanner {

        JavaVoidVisitor getVisitor(JavaContext context);

        List<Class<? extends Tree>> getApplicableTypes();

        List<String> getApplicableMethodNames();

        void visitMethod(@NonNull JavaContext context, @Nullable JavaVoidVisitor visitor,
                         @NonNull MethodInvocationTree node);
    }

    public JavaVoidVisitor getVisitor(JavaContext context) {
        return null;
    }

    public List<Class<? extends Tree>> getApplicableTypes() {
        return null;
    }

    public List<String> getApplicableMethodNames() {
        return null;
    }

    public void visitMethod(@NonNull JavaContext context, @Nullable JavaVoidVisitor visitor, @NonNull MethodInvocationTree node) {

    }
}
