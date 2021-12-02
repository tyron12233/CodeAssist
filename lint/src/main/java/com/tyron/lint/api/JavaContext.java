package com.tyron.lint.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.api.JavaProject;
import com.tyron.completion.CompileTask;
import com.tyron.lint.client.Configuration;
import com.tyron.lint.client.LintDriver;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.Trees;

import java.io.File;

public class JavaContext extends Context {
    static final String SUPPRESS_COMMENT_PREFIX = "//noinspection ";
    private CompileTask mCompileTask;

    public JavaContext(LintDriver driver, JavaProject project, File file, Configuration config) {
        super(driver, project, file, config);
    }

    public void setCompileTask(CompileTask root) {
        mCompileTask = root;
    }

    public CompileTask getCompileTask() {
        return mCompileTask;
    }

    public CompilationUnitTree getCompilationUnit() {
        return mCompileTask.root();
    }

    public void report(
            @NonNull Issue issue,
            @Nullable Tree scope,
            @Nullable Location location,
            @NonNull String message) {
        if (scope != null && mDriver.isSuppressed(this, issue, scope)) {
            return;
        }
        super.report(issue, location, message);
    }

    public Location getLocation(@NonNull Tree node) {
        SourcePositions pos = Trees.instance(mCompileTask.task).getSourcePositions();
        return Location.create(file,
                getContents(),
                (int) pos.getStartPosition(getCompilationUnit(), node),
                (int) pos.getEndPosition(getCompilationUnit(), node));
    }

    public static String getMethodName(@NonNull Tree call) {
        if (call instanceof MethodInvocationTree) {
            ExpressionTree expressionTree = ((MethodInvocationTree) call).getMethodSelect();
            if (expressionTree instanceof IdentifierTree) {
                return ((IdentifierTree) expressionTree).getName().toString();
            } else if (expressionTree instanceof MemberSelectTree) {
                return ((MemberSelectTree) expressionTree).getIdentifier().toString();
            }
        } else {
            return null;
        }
        return null;
    }
}
