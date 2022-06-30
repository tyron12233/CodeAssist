package com.tyron.lint.checks;

import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.lint.api.Category;
import com.tyron.lint.api.Detector;
import com.tyron.lint.api.Implementation;
import com.tyron.lint.api.Issue;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.api.JavaVoidVisitor;
import com.tyron.lint.api.Scope;
import com.tyron.lint.api.Severity;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.tree.JCTree;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ToastDetector extends Detector implements Detector.JavaScanner {

    public static Implementation IMPLEMENTATION = new Implementation(
            ToastDetector.class,
            Scope.JAVA_FILE_SCOPE);

    public static Issue ISSUE = Issue.create(
            "ShowToast",
            "Toast created but not shown",
            "`Toast.makeText()` creates a `Toast` but does **not** show it. You must " +
            "call `show()` on the resulting object to actually make the `Toast` appear.",
            Category.CORRECTNESS,
            6,
            Severity.WARNING,
            IMPLEMENTATION);

    @Override
    public List<String> getApplicableMethodNames() {
        return Arrays.asList("makeText", "make");
    }

    @Override
    public void visitMethod(@NonNull JavaContext context, @Nullable JavaVoidVisitor visitor, @NonNull MethodInvocationTree node) {
        ExpressionTree expressionTree = node.getMethodSelect();
        String className = "";
        String methodName = "";

        if (expressionTree instanceof MemberSelectTree) {
            MemberSelectTree memberSelectTree = (MemberSelectTree) expressionTree;
            className = ((IdentifierTree) memberSelectTree.getExpression()).getName().toString();
            methodName = ((MemberSelectTree) node.getMethodSelect()).getIdentifier().toString();
        }

        if ((className.equals("Toast") || className.equals("android.widget.Toast")) &&
                methodName.equals("makeText")) {
            List<? extends ExpressionTree> args = node.getArguments();
            if (args.size() == 3) {
                ExpressionTree duration = args.get(2);
                Log.d(null, "duration " + duration.getClass());
                if (duration instanceof JCTree.JCLiteral) {
                    context.report(ISSUE,
                            duration,
                            context.getLocation(duration),
                            "Expected duration `Toast.LENGTH_SHORT` or `Toast.LENGTH_LONG, a custom " +
                            "duration value is not supported.");
                }

            }

            checkShown(context, node, "Toast");
        }
    }

    private void checkShown(JavaContext context, MethodInvocationTree invocationTree, String toastName) {
       // TODO: Implement
    }
}
