package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.item;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.util.TreeUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import com.sun.source.tree.SwitchTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

public class SwitchConstantCompletionProvider extends BaseCompletionProvider {

    public SwitchConstantCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public void complete(CompletionList.Builder builder, CompileTask task, TreePath path,
                         String partial, boolean endsWithParen) {
        completeSwitchConstant(builder, task, path, partial);
    }

    public static void completeSwitchConstant(CompletionList.Builder builder, CompileTask task,
                                              TreePath path, String partial) {
        checkCanceled();

        if (path.getLeaf() instanceof SwitchTree) {
            SwitchTree switchTree = (SwitchTree) path.getLeaf();
            path = new TreePath(path, switchTree.getExpression());
        } else {
            TreePath parent = TreeUtil.findParentOfType(path, SwitchTree.class);
            if (parent == null) {
                return;
            }

            if (parent.getLeaf() instanceof SwitchTree) {
                path = new TreePath(parent, ((SwitchTree) parent.getLeaf()).getExpression());
            }
        }

        TypeMirror type = Trees.instance(task.task)
                .getTypeMirror(path);

        if (!(type instanceof DeclaredType)) {
            return;
        }

        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        for (Element member : task.task.getElements()
                .getAllMembers(element)) {
            if (member.getKind() != ElementKind.ENUM_CONSTANT) {
                continue;
            }
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) {
                continue;
            }
            CompletionItem item = item(member);
            item.setSortText(JavaSortCategory.UNKNOWN.toString());
            builder.addItem(item);
        }
    }
}
