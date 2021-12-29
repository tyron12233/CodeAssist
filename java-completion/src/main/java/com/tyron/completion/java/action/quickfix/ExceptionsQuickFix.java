package com.tyron.completion.java.action.quickfix;

import static com.tyron.completion.java.util.DiagnosticUtil.findMethod;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.action.CodeActionProvider;
import com.tyron.completion.java.action.api.Action;
import com.tyron.completion.java.action.api.ActionContext;
import com.tyron.completion.java.action.api.ActionProvider;
import com.tyron.completion.java.rewrite.AddCatchClause;
import com.tyron.completion.java.rewrite.AddException;
import com.tyron.completion.java.rewrite.AddTryCatch;
import com.tyron.completion.java.rewrite.Rewrite;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.completion.java.util.DiagnosticUtil.MethodPtr;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.tree.CatchTree;
import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.TryTree;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ExceptionsQuickFix extends ActionProvider {

    public static final String ERROR_CODE =
            "compiler.err.unreported.exception.need.to.catch.or.throw";

    @Override
    public boolean isApplicable(@Nullable String errorCode) {
        return ERROR_CODE.equals(errorCode);
    }

    @Override
    public List<Action> getAction(ActionContext context) {
        if (context.getDiagnostic() == null) {
            return Collections.emptyList();
        }
        CompileTask task = context.getCompileTask();
        SourcePositions sourcePositions = Trees.instance(task.task).getSourcePositions();
        Diagnostic<? extends JavaFileObject> diagnostic = context.getDiagnostic();
        String exceptionName =
                DiagnosticUtil.extractExceptionName(diagnostic.getMessage(Locale.ENGLISH));
        TreePath surroundingPath = ActionUtil.findSurroundingPath(context.getCurrentPath());
        if (surroundingPath != null) {
            List<Action> actions = new ArrayList<>();

            if (!(surroundingPath.getLeaf() instanceof LambdaExpressionTree)) {
                MethodPtr needsThrow = findMethod(task, diagnostic.getPosition());
                Rewrite rewrite = new AddException(needsThrow.className, needsThrow.methodName,
                        needsThrow.erasedParameterTypes, exceptionName);
                Action action = new Action(rewrite, "quickFix", "Add 'throws'");
                actions.add(action);
            }

            if (surroundingPath.getLeaf() instanceof TryTree) {
                TryTree tryTree = (TryTree) surroundingPath.getLeaf();
                CatchTree catchTree = tryTree.getCatches().get(tryTree.getCatches().size() - 1);
                int start = (int) sourcePositions.getEndPosition(task.root(), catchTree);

                Rewrite rewrite = new AddCatchClause(context.getCurrentFile(), start,
                        exceptionName);
                Action action = new Action(rewrite, "quickFix", "Add catch clause");
                actions.add(action);
            } else {
                int start = (int) sourcePositions.getStartPosition(task.root(),
                        surroundingPath.getLeaf());
                int end = (int) sourcePositions.getEndPosition(task.root(),
                        surroundingPath.getLeaf());
                String contents = surroundingPath.getLeaf().toString();
                Rewrite rewrite = new AddTryCatch(context.getCurrentFile(), contents, start, end,
                        exceptionName);
                Action action = new Action(rewrite, "quickFix", "Surround with try catch");
                actions.add(action);
            }
            return actions;
        } return Collections.emptyList();
    }


}
