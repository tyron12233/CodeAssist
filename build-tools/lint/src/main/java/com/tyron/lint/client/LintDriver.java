package com.tyron.lint.client;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.model.Project;
import com.tyron.lint.api.Context;
import com.tyron.lint.api.Detector;
import com.tyron.lint.api.Issue;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.api.Location;
import com.tyron.lint.api.Scope;
import com.tyron.lint.api.Severity;
import com.tyron.lint.api.TextFormat;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public class LintDriver {

    private static final String STUDIO_ID_PREFIX = "AndroidLint";
    private final LintClient mClient;
    //private LintRequest mRequest;
    private IssueRegistry mRegistry;
    private volatile boolean mCanceled;
    private EnumSet<Scope> mScope;
    private List<? extends Detector> mApplicableDetectors;
    private Map<Scope, List<Detector>> mScopeDetectors;
//    private List<LintListener> mListeners;
    private int mPhase;
    private List<Detector> mRepeatingDetectors;
    private EnumSet<Scope> mRepeatScope;
    private Project[] mCurrentProjects;
    private Project mCurrentProject;
    private boolean mAbbreviating = true;
    private boolean mParserErrors;
    private Map<Object,Object> mProperties;


    /**
     * Creates a new {@link LintDriver}
     *
     * @param registry The registry containing issues to be checked
     * @param client the tool wrapping the analyzer, such as an IDE or a CLI
     */
    public LintDriver(@NonNull IssueRegistry registry, @NonNull LintClient client) {
        mRegistry = registry;
        mClient = new LintClientWrapper(client);
    }

    public LintClient getClient() {
        return mClient;
    }

    public boolean isSuppressed(@Nullable JavaContext context, @NonNull Issue issue,
                                @Nullable Tree scope) {
        boolean checkComments = mClient.checkForSuppressComments() &&
                context != null && context.containsCommentSuppress();
        if (context == null) {
            return false;
        }
        while (scope != null) {
            if (scope instanceof MethodTree) {
                if (isSuppressed(issue, ((MethodTree) scope).getModifiers())) {
                    return true;
                }
            }

            TreePath parentPath = TreePath.getPath(context.getCompilationUnit(), scope).getParentPath();
            if (parentPath == null) {
                break;
            } else {
                scope = parentPath.getLeaf();
            }
        }

        return false;
    }

    public boolean isSuppressed(@Nullable Issue issue, @Nullable ModifiersTree modifiers) {
        if (modifiers == null) {
            return false;
        }

        List<? extends AnnotationTree> annotations = modifiers.getAnnotations();
        if (annotations == null) {
            return false;
        }

        for (AnnotationTree annotation : annotations) {
            IdentifierTree type = (IdentifierTree) annotation.getAnnotationType();
            String typeName = type.getName().toString();
            if (typeName.endsWith("SuppressLint")
                || typeName.endsWith("SuppressWarnings")) {
                List<? extends ExpressionTree> values = annotation.getArguments();
                if (values != null) {
                    for (ExpressionTree arg : values) {
                        if (arg == null) {
                            continue;
                        }
                        if (arg instanceof LiteralTree) {
                            String value = String.valueOf(((LiteralTree) arg).getValue());
                            if (matches(issue, value)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private static boolean matches(@Nullable Issue issue, @NonNull String id) {
        if (id.equalsIgnoreCase("all")) {
            return true;
        }

        if (issue != null) {
            String issueId = issue.getId();
            if (id.equalsIgnoreCase(issueId)) {
                return true;
            }
            if (id.startsWith(STUDIO_ID_PREFIX)
                    && id.regionMatches(true, STUDIO_ID_PREFIX.length(), issueId, 0, issueId.length())
                    && id.substring(STUDIO_ID_PREFIX.length()).equalsIgnoreCase(issueId)) {
                return true;
            }
        }

        return false;
    }

    private static class LintClientWrapper extends LintClient {
        private LintClient mDelegate;

        public LintClientWrapper(LintClient client) {
            mDelegate = client;
        }

        @NonNull
        @Override
        public Class<? extends Detector> replaceDetector(@NonNull Class<? extends Detector> detectorClass) {
            return mDelegate.replaceDetector(detectorClass);
        }

        @Override
        public boolean checkForSuppressComments() {
            return mDelegate.checkForSuppressComments();
        }

        @Override
        public void report(@NonNull Context context, @NonNull Issue issue, @NonNull Severity severity, @Nullable Location location, @NonNull String message, @NonNull TextFormat format) {
            mDelegate.report(context, issue, severity, location, message, format);
        }

        @Override
        public void log(Throwable t, String s, String name) {
            mDelegate.log(t, s, name);
        }
    }
}
