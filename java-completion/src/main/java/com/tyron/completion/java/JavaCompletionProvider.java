package com.tyron.completion.java;

import android.util.Log;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.provider.Completions;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class JavaCompletionProvider extends CompletionProvider {

    private CachedCompletion mCachedCompletion;

    public JavaCompletionProvider() {

    }

    @Override
    public boolean accept(File file) {
        return file.isFile() && file.getName().endsWith(".java");
    }

    @Override
    public CompletionList complete(CompletionParameters params) {
        if (!(params.getModule() instanceof JavaModule)) {
            return CompletionList.EMPTY;
        }
        ProgressManager.checkCanceled();

        if (isIncrementalCompletion(mCachedCompletion, params)) {
            String partial = partialIdentifier(params.getPrefix(),
                    params.getPrefix().length());
            CompletionList cachedList = mCachedCompletion.getCompletionList();
            if (!cachedList.items.isEmpty()) {
                List<CompletionItem> narrowedList =
                        cachedList.items.stream().filter(item -> getRatio(item, partial) > 70)
                                .sorted(Comparator.comparingInt((CompletionItem it) -> getRatio(it, partial)).reversed())
                                .collect(Collectors.toList());
                CompletionList completionList = new CompletionList();
                completionList.items = narrowedList;
                return completionList;
            }
        }

        try {
            CompletionList complete = complete(params.getProject(),
                    (JavaModule) params.getModule(), params.getFile(), params.getContents(),
                    params.getIndex());
            String newPrefix = params.getPrefix();
            if (params.getPrefix().contains(".")) {
                newPrefix = partialIdentifier(params.getPrefix(), params.getPrefix().length());
            }
            mCachedCompletion = new CachedCompletion(params.getFile(), params.getLine(),
                    params.getColumn(), newPrefix, complete);
            return complete;
        } catch (ProcessCanceledException e) {
            mCachedCompletion = null;
            return CompletionList.EMPTY;
        }
    }

    public CompletionList complete(Project project, JavaModule module, File file, String contents
            , long cursor) {
        JavaCompilerProvider compilerProvider =
                CompilerService.getInstance().getIndex(JavaCompilerProvider.KEY);
        JavaCompilerService service = compilerProvider.getCompiler(project, module);

        try {
            return new Completions(service).complete(file, contents, cursor);
        } catch (Throwable e) {
            if (e instanceof ProcessCanceledException) {
                throw e;
            }
            if (BuildConfig.DEBUG) {
                Log.e("JavaCompletionProvider", "Unable to get completions", e);
            }
            service.close();
        } finally {
            service.close();
        }
        return CompletionList.EMPTY;
    }

    private String partialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private int getRatio(CompletionItem item, String partialIdentifier) {
        String label = getLabel(item);
        return FuzzySearch.partialRatio(label, partialIdentifier);
    }

    private String getLabel(CompletionItem item) {
        String label = item.label;
        if (label.contains("(")) {
            label = label.substring(0, label.indexOf('('));
        }
        return label;
    }

    private boolean isIncrementalCompletion(CachedCompletion cachedCompletion,
                                            CompletionParameters params) {
        String prefix = params.getPrefix();
        File file = params.getFile();
        int line = params.getLine();
        int column = params.getColumn();
        prefix = partialIdentifier(prefix, prefix.length());

        if (line == -1) {
            return false;
        }

        if (column == -1) {
            return false;
        }

        if (cachedCompletion == null) {
            return false;
        }

        if (!file.equals(cachedCompletion.getFile())) {
            return false;
        }

        if (prefix.endsWith(".")) {
            return false;
        }

        if (cachedCompletion.getLine() != line) {
            return false;
        }

        if (cachedCompletion.getColumn() > column) {
            return false;
        }

        if (!prefix.startsWith(cachedCompletion.getPrefix())) {
            return false;
        }

        return prefix.length() - cachedCompletion.getPrefix().length() == column - cachedCompletion.getColumn();
    }
}
