package com.tyron.lint.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.api.JavaModule;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.lint.client.Configuration;
import com.tyron.lint.client.LintDriver;

import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnonymousClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiField;
import org.jetbrains.kotlin.com.intellij.psi.PsiLabeledStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import java.io.File;

public class JavaContext extends Context {
    static final String SUPPRESS_COMMENT_PREFIX = "//noinspection ";
    private CompileTask mCompileTask;

    public JavaContext(LintDriver driver, JavaModule project, File file, Configuration config) {
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

    /**
     * Searches for a name node corresponding to the given node
     * @return the name node to use, if applicable
     */
    @Nullable
    public static PsiElement findNameElement(@NonNull PsiElement element) {
        if (element instanceof PsiClass) {
            if (element instanceof PsiAnonymousClass) {
                return ((PsiAnonymousClass)element).getBaseClassReference();
            }
            return ((PsiClass) element).getNameIdentifier();
        } else if (element instanceof PsiMethod) {
            return ((PsiMethod) element).getNameIdentifier();
        } else if (element instanceof PsiMethodCallExpression) {
            return ((PsiMethodCallExpression) element).getMethodExpression().
                    getReferenceNameElement();
        } else if (element instanceof PsiNewExpression) {
            return ((PsiNewExpression) element).getClassReference();
        } else if (element instanceof PsiField) {
            return ((PsiField)element).getNameIdentifier();
        } else if (element instanceof PsiAnnotation) {
            return ((PsiAnnotation)element).getNameReferenceElement();
        } else if (element instanceof PsiReferenceExpression) {
            return ((PsiReferenceExpression) element).getReferenceNameElement();
        } else if (element instanceof PsiLabeledStatement) {
            return ((PsiLabeledStatement)element).getLabelIdentifier();
        }
        return null;
    }
}
