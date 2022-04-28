package com.tyron.lint.checks;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.lint.api.Category;
import com.tyron.lint.api.Detector;
import com.tyron.lint.api.Implementation;
import com.tyron.lint.api.Issue;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.api.JavaVoidVisitor;
import com.tyron.lint.api.Location;
import com.tyron.lint.api.Scope;
import com.tyron.lint.api.Severity;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.Collections;
import java.util.List;

/**
 * Makes sure that methods call super when overriding methods.
 */
public class CallSuperDetector extends Detector implements Detector.JavaScanner {

    private static final String CALL_SUPER_ANNOTATION = "androidx.annotation." + "CallSuper"; //$NON-NLS-1$
    private static final String ON_DETACHED_FROM_WINDOW = "onDetachedFromWindow";   //$NON-NLS-1$
    private static final String ON_VISIBILITY_CHANGED = "onVisibilityChanged";      //$NON-NLS-1$

    private static final Implementation IMPLEMENTATION = new Implementation(
            CallSuperDetector.class,
            Scope.JAVA_FILE_SCOPE);

    /** Missing call to super */
    public static final Issue ISSUE = Issue.create(
            "MissingSuperCall", //$NON-NLS-1$
            "Missing Super Call",

            "Some methods, such as `View#onDetachedFromWindow`, require that you also " +
                    "call the super implementation as part of your method.",

            Category.CORRECTNESS,
            9,
            Severity.ERROR,
            IMPLEMENTATION);

    /** Constructs a new {@link CallSuperDetector} check */
    public CallSuperDetector() {
    }

    @Override
    public List<Class<? extends Tree>> getApplicableTypes() {
        return Collections.singletonList(MethodTree.class);
    }

    @Override
    public JavaVoidVisitor getVisitor(JavaContext context) {
        return new JavaVoidVisitor() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                checkCallSuper(context, node);
                return null;
            }
        };
    }

    private static void checkCallSuper(@NonNull JavaContext context,
            @NonNull MethodTree declaration) {
        ExecutableElement superMethod = getRequiredSuperMethod(context, declaration);
        if (superMethod != null) {
            if (!SuperCallVisitor.callsSuper(context, declaration, superMethod)) {
                String methodName = declaration.getName().toString();
                String message = "Overriding method should call `super."
                        + methodName + "`";
                Location location = context.getLocation(declaration);
                context.report(ISSUE, declaration, location, message);
            }
        }
    }
    /**
     * Checks whether the given method overrides a method which requires the super method
     * to be invoked, and if so, returns it (otherwise returns null)
     */
    @Nullable
    private static ExecutableElement getRequiredSuperMethod(@NonNull JavaContext context, @NonNull MethodTree node) {
        Trees trees = Trees.instance(context.getCompileTask().task);
        TreePath path = TreePath.getPath(context.getCompilationUnit(), node);
        Element method = trees.getElement(path);
        TypeElement typeElement = trees.getScope(path).getEnclosingClass();
        DeclaredType superClass = (DeclaredType) typeElement.getSuperclass();
        TypeElement superElement = (TypeElement) superClass.asElement();

        List<? extends Element> elements = context.getCompileTask().task.getElements().getAllMembers(superElement);
        for (Element element : elements) {
            if (element.getKind() != ElementKind.METHOD) {
                continue;
            }

            if (element.getAnnotation(CallSuper.class) == null) {
                continue;
            }

            if (element.getSimpleName().equals(method.getSimpleName())) {
                return (ExecutableElement) element;
            }
        }

        return null;
    }

    private static class SuperCallVisitor extends JavaVoidVisitor {

        private final JavaContext mContext;
        private final ExecutableElement mMethod;
        private boolean mCallsSuper;

        public static boolean callsSuper(
                @NonNull JavaContext context,
                @NonNull MethodTree methodDeclaration,
                @NonNull ExecutableElement method) {
            SuperCallVisitor visitor = new SuperCallVisitor(context, method);
            methodDeclaration.accept(visitor, null);
            return visitor.mCallsSuper;
        }

        private SuperCallVisitor(@NonNull JavaContext context, @NonNull ExecutableElement method) {
            mContext = context;
            mMethod = method;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree methodInvocationTree, Void unused) {
            ExpressionTree expressionTree = methodInvocationTree.getMethodSelect();
            if (expressionTree instanceof MemberSelectTree) {
                boolean isThis = ((MemberSelectTree) expressionTree).getExpression().toString().equals("super");
                if (isThis) {
                    if (mMethod.getSimpleName().contentEquals(((MemberSelectTree) expressionTree).getIdentifier().toString())) {
                        mCallsSuper = true;
                        return null;
                    }
                }
            }
            return null;
        }
    }
 }
