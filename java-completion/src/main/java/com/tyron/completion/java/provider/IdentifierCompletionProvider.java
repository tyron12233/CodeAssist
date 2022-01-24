package com.tyron.completion.java.provider;

import static com.tyron.completion.java.provider.ImportCompletionProvider.addStaticImports;
import static com.tyron.completion.java.provider.SwitchConstantCompletionProvider.completeSwitchConstant;
import static com.tyron.completion.java.util.CompletionItemFactory.classItem;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CompletionList;

import org.openjdk.source.tree.CaseTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.util.TreePath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class IdentifierCompletionProvider extends BaseCompletionProvider {

    public IdentifierCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public CompletionList complete(CompileTask task, TreePath path, String partial,
                                   boolean endsWithParen) {
        checkCanceled();

        if (path.getParentPath().getLeaf() instanceof CaseTree) {
            return completeSwitchConstant(task, path.getParentPath(), partial);
        }

        CompletionList list = new CompletionList();
        CompletionList complete = new ScopeCompletionProvider(getCompiler())
                .complete(task, path, partial, endsWithParen);
        list.items.addAll(complete.items);
        if (partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addClassNames(path.getCompilationUnit(), partial, list);
        }
        addStaticImports(task, path.getCompilationUnit(), partial, endsWithParen, list);

        new KeywordCompletionProvider(getCompiler()).complete(task, path, partial, endsWithParen);

        return list;
    }

    private void addClassNames(CompilationUnitTree root, String partial, CompletionList list) {
        checkCanceled();

        String packageName = Objects.toString(root.getPackageName(), "");
        Set<String> uniques = new HashSet<>();
        for (String className : getCompiler().packagePrivateTopLevelTypes(packageName)) {
            if (!StringSearch.matchesPartialName(className, partial)) continue;
            list.items.add(classItem(className));
            uniques.add(className);
        }
        for (String className : getCompiler().publicTopLevelTypes()) {
            if (FuzzySearch.partialRatio(className, partial) < 90) continue;
            if (uniques.contains(className)) continue;
            list.items.add(classItem(className));
            uniques.add(className);
        }
    }

}
