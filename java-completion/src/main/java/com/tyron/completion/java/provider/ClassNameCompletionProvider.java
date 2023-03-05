package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.classItem;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.tyron.builder.project.api.Module;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.ShortNamesCache;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.insert.ClassImportInsertHandler;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class ClassNameCompletionProvider extends BaseCompletionProvider {

    public ClassNameCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public void complete(CompletionList.Builder builder,
                         JavacUtilitiesProvider task,
                         TreePath path,
                         String partial,
                         boolean endsWithParen) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        boolean caseSensitiveMatch =
                !preferences.getBoolean(SharedPreferenceKeys.JAVA_CASE_INSENSITIVE_MATCH, false);
        addClassNames(task.root(), partial, builder, task, caseSensitiveMatch);
    }

    public static void addClassNames(CompilationUnitTree root,
                                     String partial,
                                     CompletionList.Builder list,
                                     JavacUtilitiesProvider task,
                                     boolean caseSensitive) {

        List<String> fullyQualifiedNames =
                getFullyQualifiedNames(root, partial, task, caseSensitive);
        for (String className : fullyQualifiedNames) {
            CompletionItem item = classItem(className);
            item.data = className;
            item.setInsertHandler(
                    new ClassImportInsertHandler(task, new File(root.getSourceFile().toUri()),
                            item));
            item.setSortText(JavaSortCategory.TO_IMPORT.toString());
            list.addItem(item);
        }
    }

    public static List<String> getFullyQualifiedNames(CompilationUnitTree root,
                                                      String partial,
                                                      JavacUtilitiesProvider task,
                                                      boolean caseSensitive) {
        checkCanceled();

        Predicate<String> predicate;
        if (caseSensitive) {
            predicate = string -> StringSearch.matchesPartialName(string, partial);
        } else {
            predicate = string -> StringSearch.matchesPartialNameLowercase(string, partial);
        }

        Set<String> uniques = new HashSet<>();
        File fileToComplete = new File(root.getSourceFile().toUri());
        final Module module = task.getProject().getModule(fileToComplete);
        ShortNamesCache cache = ShortNamesCache.getInstance(module);

        for (String className : cache.getAllClassNames()) {
            // more strict on matching class names
            String simpleName = ActionUtil.getSimpleName(className);
            if (!predicate.test(simpleName)) {
                continue;
            }
            if (uniques.contains(className)) {
                continue;
            }

            uniques.add(className);
        }

        return new ArrayList<>(uniques);
    }
}
