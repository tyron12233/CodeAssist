package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.item;
import static com.tyron.completion.java.util.CompletionItemFactory.method;
import static com.tyron.completion.java.util.CompletionItemFactory.overridableMethod;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.util.ElementUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.source.tree.Scope;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class ScopeCompletionProvider extends BaseCompletionProvider {

    public ScopeCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public CompletionList complete(CompileTask task, TreePath path, String partial,
                                   boolean endsWithParen) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        Set<CompletionItem> list = new HashSet<>();
        Scope scope = trees.getScope(path);

        Predicate<CharSequence> filter = p1 -> {
            String label = p1.toString();
            if (label.contains("(")) {
                label = label.substring(0, label.indexOf('('));
            }
            return FuzzySearch.partialRatio(label, partial) >= 70;
        };

        for (Element element : ScopeHelper.scopeMembers(task, scope, filter)) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement executableElement = (ExecutableElement) element;
                TreePath parentPath = path.getParentPath().getParentPath();
                Tree parentLeaf = parentPath.getLeaf();
                if (parentLeaf.getKind() == Tree.Kind.CLASS && !ElementUtil.isFinal(executableElement)) {
                    list.addAll(overridableMethod(task, parentPath,
                            Collections.singletonList(executableElement), endsWithParen));
                } else {
                    list.addAll(method(task, Collections.singletonList(executableElement),
                            endsWithParen, false, (ExecutableType) executableElement.asType()));
                }
            } else {
                list.add(item(element));
            }
        }
        CompletionList completionList = new CompletionList();
        completionList.items.addAll(list);
        return completionList;
    }
}
