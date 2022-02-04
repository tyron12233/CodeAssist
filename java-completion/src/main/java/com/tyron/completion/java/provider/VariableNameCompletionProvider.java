package com.tyron.completion.java.provider;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.CompletionItemFactory;
import com.tyron.completion.model.CompletionList;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.tree.VariableTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

public class VariableNameCompletionProvider extends BaseCompletionProvider {

    public VariableNameCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public CompletionList complete(CompileTask task,
                                   TreePath path,
                                   String partial,
                                   boolean endsWithParen) {
        Element element = Trees.instance(task.task).getElement(path);
        if (element == null) {
            return CompletionList.EMPTY;
        }
        TypeMirror type = element.asType();
        if (type == null) {
            return CompletionList.EMPTY;
        }

        String name = ActionUtil.guessNameFromType(type);
        if (name == null) {
            return CompletionList.EMPTY;
        }

        CompletionList list = new CompletionList();
        list.items.add(CompletionItemFactory.item(name));
        return list;
    }
}
