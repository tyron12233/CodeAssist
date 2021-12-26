package com.tyron.completion.java.action;

import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.model.CodeAction;
import com.tyron.completion.java.model.CodeActionList;

import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.util.TreePath;

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
