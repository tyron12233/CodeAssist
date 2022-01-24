package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.item;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.util.TreeUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.tree.SwitchTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.util.ArrayList;
import java.util.List;

public class SwitchConstantCompletionProvider extends BaseCompletionProvider {

    public SwitchConstantCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public CompletionList complete(CompileTask task, TreePath path, String partial,
                                   boolean endsWithParen) {
        return completeSwitchConstant(task, path, partial);
    }

    public static CompletionList completeSwitchConstant(CompileTask task, TreePath path, String partial) {
        checkCanceled();

        if (path.getLeaf() instanceof SwitchTree) {
            SwitchTree switchTree = (SwitchTree) path.getLeaf();
            path = new TreePath(path, switchTree.getExpression());
        } else {
            TreePath parent = TreeUtil.findParentOfType(path, SwitchTree.class);
            if (parent == null) {
                return CompletionList.EMPTY;
            }

            if (parent.getLeaf() instanceof SwitchTree) {
                path = new TreePath(parent, ((SwitchTree) parent.getLeaf()).getExpression());
            }
        }

        TypeMirror type = Trees.instance(task.task).getTypeMirror(path);

        if (!(type instanceof DeclaredType)) {
            return new CompletionList();
        }
        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        List<CompletionItem> list = new ArrayList<>();
        for (Element member : task.task.getElements().getAllMembers(element)) {
            if (member.getKind() != ElementKind.ENUM_CONSTANT) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            list.add(item(member));
        }

        CompletionList comp = new CompletionList();
        comp.isIncomplete = false;
        comp.items = list;
        return comp;
    }
}
