package com.tyron.completion.java.provider;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.CompletionItemFactory;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.List;

/**
 * Responsive for suggesting unique variable names. If a variable already exists, it is appended
 * with a number. If the existing variable ends with a number one is added to it.
 */
public class VariableNameCompletionProvider extends BaseCompletionProvider {

    public VariableNameCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public void complete(CompletionList.Builder builder, CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        Element element = Trees.instance(task.task).getElement(path);
        if (element == null) {
            return;
        }
        TypeMirror type = element.asType();
        if (type == null) {
            return;
        }

        List<String> names = ActionUtil.guessNamesFromType(type);
        if (names.isEmpty()) {
            return;
        }

        for (String name : names) {
            while (ActionUtil.containsVariableAtScope(name, task, path)) {
                name = ActionUtil.getVariableName(name);
            }
            CompletionItem item = CompletionItemFactory.item(name);
            item.setSortText(JavaSortCategory.UNKNOWN.toString());
            builder.addItem(item);
        }
    }
}
