package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.classItem;
import static com.tyron.completion.java.util.CompletionItemFactory.item;
import static com.tyron.completion.java.util.CompletionItemFactory.method;
import static com.tyron.completion.java.util.CompletionItemFactory.overridableMethod;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.util.ElementUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ExecutableType;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class ScopeCompletionProvider extends BaseCompletionProvider {

    public ScopeCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public void complete(CompletionList.Builder builder, CompileTask task, TreePath path,
                         String partial, boolean endsWithParen) {
        checkCanceled();
        addCompletionItems(task, path, partial, endsWithParen, builder);
    }

    public static void addCompletionItems(CompileTask task, TreePath path, String partial,
                                          boolean endsWithParen, CompletionList.Builder builder) {
        Trees trees = task.getTrees();
        Scope scope = trees.getScope(path);

        Predicate<CharSequence> filter = p1 -> {
            String label = p1.toString();
            if (label.contains("(")) {
                label = label.substring(0, label.indexOf('('));
            }
            return FuzzySearch.tokenSetPartialRatio(label, partial) >= 70;
        };

        TreePath parentPath = path.getParentPath()
                .getParentPath();
        Tree parentLeaf = parentPath.getLeaf();

        for (Element element : ScopeHelper.scopeMembers(task, scope, filter)) {
            checkCanceled();

            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement executableElement = (ExecutableElement) element;
                String sortText = "";
                if (Objects.equals(scope.getEnclosingClass(), element.getEnclosingElement())) {
                    sortText = JavaSortCategory.DIRECT_MEMBER.toString();
                } else {
                    sortText = JavaSortCategory.ACCESSIBLE_SYMBOL.toString();
                }
                if (parentLeaf.getKind() == Tree.Kind.CLASS &&
                    !ElementUtil.isFinal(executableElement)) {
                    builder.addItems(overridableMethod(task, parentPath,
                                                       Collections.singletonList(executableElement),
                                                       endsWithParen), sortText);
                } else {
                    builder.addItems(method(task, Collections.singletonList(executableElement),
                                            endsWithParen, false,
                                            (ExecutableType) executableElement.asType()), sortText);
                }
            } else {
                CompletionItem item = item(element);
                if (Objects.equals(scope.getEnclosingClass(), element.getEnclosingElement())) {
                    item.setSortText(JavaSortCategory.DIRECT_MEMBER.toString());
                } else if (Objects.nonNull(scope.getEnclosingMethod()) &&
                           Objects.equals(scope.getEnclosingMethod(),
                                          element.getEnclosingElement())) {
                    item.setSortText(JavaSortCategory.LOCAL_VARIABLE.toString());
                } else {
                    item.setSortText(JavaSortCategory.ACCESSIBLE_SYMBOL.toString());
                }
                builder.addItem(item);
            }
        }

        CompilationUnitTree root = task.root();
        if (root != null) {
            List<? extends Tree> typeDecls = root.getTypeDecls();
            List<ClassTree> classTrees = typeDecls.stream()
                    .filter(it -> it instanceof ClassTree)
                    .map(it -> (ClassTree) it)
                    .collect(Collectors.toList());
            for (ClassTree classTree : classTrees) {
                CompletionItem item = classItem(classTree.getSimpleName().toString());
                item.setSortText(JavaSortCategory.ACCESSIBLE_SYMBOL.toString());
                builder.addItem(item);
            }
        }
    }
}
