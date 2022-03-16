package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.classItem;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.tyron.common.ApplicationProvider;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.insert.ClassImportInsertHandler;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;

import java.io.File;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public class ClassNameCompletionProvider extends BaseCompletionProvider {

    public ClassNameCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public void complete(CompletionList.Builder builder,
                         CompileTask task,
                         TreePath path,
                         String partial,
                         boolean endsWithParen) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        boolean caseSensitiveMatch =
                !preferences.getBoolean(SharedPreferenceKeys.JAVA_CASE_INSENSITIVE_MATCH, false);
        addClassNames(task.root(), partial, builder, getCompiler(), caseSensitiveMatch);
    }

    public static void addClassNames(CompilationUnitTree root,
                                     String partial,
                                     CompletionList.Builder list,
                                     JavaCompilerService compiler,
                                     boolean caseSensitive) {
        checkCanceled();

        Predicate<String> predicate;
        if (caseSensitive) {
            predicate = string -> StringSearch.matchesPartialName(string, partial);
        } else {
            predicate = string -> StringSearch.matchesPartialNameLowercase(string, partial);
        }

        String packageName = Objects.toString(root.getPackageName(), "");
        Set<String> uniques = new HashSet<>();
        for (String className : compiler.packagePrivateTopLevelTypes(packageName)) {
            if (!predicate.test(className)) {
                continue;
            }
            list.addItem(classItem(className));
            uniques.add(className);
        }

        for (String className : compiler.publicTopLevelTypes()) {
            // more strict on matching class names
            String simpleName = ActionUtil.getSimpleName(className);
            if (!predicate.test(simpleName)) {
                continue;
            }
            if (uniques.contains(className)) {
                continue;
            }
            if (list.getItemCount() >= Completions.MAX_COMPLETION_ITEMS) {
                list.incomplete();
                break;
            }
            CompletionItem item = classItem(className);
            item.data = className;
            item.setInsertHandler(new ClassImportInsertHandler(compiler, new File(
                    root.getSourceFile()
                            .toUri()), item));
            item.setSortText(JavaSortCategory.TO_IMPORT.toString());
            list.addItem(item);
            uniques.add(className);
        }
    }
}
