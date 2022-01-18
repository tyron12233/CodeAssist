package com.tyron.completion.java.action.context;

import android.view.MenuItem;

import androidx.annotation.NonNull;

import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.api.Action;
import com.tyron.completion.java.action.api.ActionContext;
import com.tyron.completion.java.action.api.ActionProvider;
import com.tyron.completion.java.rewrite.IntroduceLocalVariable;
import com.tyron.completion.java.util.ActionUtil;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeParameterElement;
import org.openjdk.javax.lang.model.type.TypeKind;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.tree.Scope;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.util.List;

public class IntroduceLocalVariableAction extends ActionProvider {

    @Override
    public boolean isApplicable(ActionContext context, @NonNull TreePath currentPath) {
        return ActionUtil.canIntroduceLocalVariable(currentPath);
    }

    @Override
    public void addMenus(@NonNull ActionContext context) {
        CompileTask task = context.getCompileTask();
        TreePath path = context.getCurrentPath();
        Element element = Trees.instance(task.task).getElement(path);
        if (element instanceof ExecutableElement) {
            TypeMirror returnType = ActionUtil.getReturnType(task.task, path,
                    (ExecutableElement) element);
            if (returnType.getKind() != TypeKind.VOID) {
                SourcePositions pos = Trees.instance(task.task).getSourcePositions();
                long startPosition = pos.getStartPosition(path.getCompilationUnit(),
                        path.getLeaf());
                String title = context.getContext().getString(R.string.menu_quickfix_introduce_local_variable_title);
                MenuItem item = context.addMenu("context", title);
                item.setOnMenuItemClickListener(i -> {
                    context.performAction(new Action(new IntroduceLocalVariable(context.getCurrentFile(), element.getSimpleName().toString(), returnType, startPosition)));
                    return true;
                });
            }
        }
    }
}
