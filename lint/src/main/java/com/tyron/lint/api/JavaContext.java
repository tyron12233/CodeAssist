package com.tyron.lint.api;

import androidx.annotation.NonNull;

import com.tyron.completion.CompileTask;

import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.Tree;

import java.io.File;

public class JavaContext extends Context {

    private CompileTask mCompileTask;

    public JavaContext(File file) {
        super(file);
    }

    public void setCompileTask(CompileTask root) {
        mCompileTask = root;
    }

    public static String getMethodName(@NonNull Tree call) {
        if (call instanceof MethodInvocationTree) {
            return ((ExecutableElement)call).getSimpleName().toString();
        } else {
            return null;
        }
    }
}
