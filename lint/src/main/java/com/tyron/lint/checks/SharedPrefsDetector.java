package com.tyron.lint.checks;

import android.util.Log;

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

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.code.Symbol;

import java.util.Collections;
import java.util.List;

public class SharedPrefsDetector extends Detector implements Detector.JavaScanner {
    /** The main issue discovered by this detector */
    public static final Issue ISSUE = Issue.create(
            "CommitPrefEdits", //$NON-NLS-1$
            "Missing `commit()` on `SharedPreference` editor",

            "After calling `edit()` on a `SharedPreference`, you must call `commit()` " +
                    "or `apply()` on the editor to save the results.",

            Category.CORRECTNESS,
            6,
            Severity.WARNING,
            new Implementation(
                    SharedPrefsDetector.class,
                    Scope.JAVA_FILE_SCOPE));

    public static final String ANDROID_CONTENT_SHARED_PREFERENCES =
            "android.content.SharedPreferences";
    private static final String ANDROID_CONTENT_SHARED_PREFERENCES_EDITOR =
            "android.content.SharedPreferences.Editor";

    /** Constructs a new {@link SharedPrefsDetector} check */
    public SharedPrefsDetector() {
    }

    @Override
    public List<String> getApplicableMethodNames() {
        return Collections.singletonList("edit");
    }

    @Override
    public void visitMethod(@NonNull JavaContext context, @Nullable JavaVoidVisitor visitor, @NonNull MethodInvocationTree node) {
        assert JavaContext.getMethodName(node).equals("edit");

        TreePath path = TreePath.getPath(context.getCompilationUnit(), node);
        Element element = Trees.instance(context.getCompileTask().task).getElement(path);
        ExecutableElement resolved = (ExecutableElement) element;
        boolean verifiedType = resolved.getReturnType().toString().equals(ANDROID_CONTENT_SHARED_PREFERENCES_EDITOR);

        super.visitMethod(context, visitor, node);
    }
}
