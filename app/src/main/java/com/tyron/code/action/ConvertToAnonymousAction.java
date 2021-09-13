package com.tyron.code.action;

import com.tyron.code.completion.CompileTask;
import com.tyron.code.model.CodeAction;
import com.tyron.code.model.CodeActionList;

import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.Tree;

import java.util.Collections;

public class ConvertToAnonymousAction extends IAction {

    @Override
    public boolean isApplicable(Tree tree) {
        return tree instanceof LambdaExpressionTree;
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
