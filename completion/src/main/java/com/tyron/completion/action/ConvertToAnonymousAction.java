package com.tyron.completion.action;

import com.tyron.completion.CompileTask;
import com.tyron.completion.model.CodeAction;
import com.tyron.completion.model.CodeActionList;

import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.util.TreePath;

import java.util.Collections;

public class ConvertToAnonymousAction extends IAction {

    @Override
    public boolean isApplicable(TreePath tree, CompileTask task) {
        return tree.getLeaf() instanceof LambdaExpressionTree;
    }

    @Override
    public CodeActionList get(CompileTask task) {
        CodeActionList actionList = new CodeActionList();
        actionList.setTitle("Convert to anonymous class");

        CodeAction action = new CodeAction();
        action.setTitle("Convert to anonymous class");
        action.setEdits(Collections.emptyMap());
        actionList.setActions(Collections.singletonList(action));

        return actionList;
    }
}
