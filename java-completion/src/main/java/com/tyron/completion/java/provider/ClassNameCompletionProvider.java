package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.classItem;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.JavaCompletionProvider;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CompletionList;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.util.TreePath;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class ClassNameCompletionProvider extends BaseCompletionProvider {

    public ClassNameCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public CompletionList complete(CompileTask task, TreePath path, String partial,
                                   boolean endsWithParen) {
        CompletionList list = new CompletionList();
        addClassNames(task.root(), partial, list, getCompiler());
        return list;
    }

    public static void addClassNames(CompilationUnitTree root, String partial, CompletionList list, JavaCompilerService compiler) {
        checkCanceled();

        String packageName = Objects.toString(root.getPackageName(), "");
        Set<String> uniques = new HashSet<>();
        for (String className : compiler.packagePrivateTopLevelTypes(packageName)) {
            if (!StringSearch.matchesPartialName(className, partial)) continue;
            list.items.add(classItem(className));
            uniques.add(className);
        }
        for (String className : compiler.publicTopLevelTypes()) {
            if (uniques.size() >= Completions.MAX_COMPLETION_ITEMS) {
                list.isIncomplete = true;
                break;
            }
            if (FuzzySearch.partialRatio(className, partial) < 70) continue;
            if (uniques.contains(className)) continue;
            list.items.add(classItem(className));
            uniques.add(className);
        }
    }
}
