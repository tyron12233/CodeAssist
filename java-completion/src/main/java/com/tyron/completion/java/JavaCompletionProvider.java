package com.tyron.completion.java;

import android.util.Log;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;

import java.io.File;
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
    public CompletionList complete(Project project, Module module, File file, String contents,
                                   String prefix, int line, int column, long index) {
        if (!(module instanceof JavaModule)) {
            return CompletionList.EMPTY;
        }
        ProgressManager.checkCanceled();

        if (isIncrementalCompletion(mCachedCompletion, file, prefix, line, column)) {
            String partialIdentifier = partialIdentifier(prefix, prefix.length());
            CompletionList cachedList = mCachedCompletion.getCompletionList();
            if (!cachedList.items.isEmpty()) {
                List<CompletionItem> narrowedList =
                        cachedList.items.stream().filter(item -> {
                            String label = item.label;
                            if (label.contains("(")) {
                                label = label.substring(0, label.indexOf('('));
                            }
                            if (label.length() < partialIdentifier.length()) {
                                return false;
                            }
                            return FuzzySearch.partialRatio(label, partialIdentifier) > 70;
                        }).collect(Collectors.toList());
                CompletionList completionList = new CompletionList();
                completionList.items = narrowedList;
                return completionList;
            }
        }

        try {
            CompletionList complete = complete(project, (JavaModule) module, file, contents, index);
            String newPrefix = prefix;
            if (prefix.contains(".")) {
                newPrefix = partialIdentifier(prefix, prefix.length());
            }
            mCachedCompletion = new CachedCompletion(file, line, column, newPrefix, complete);
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
            return new com.tyron.completion.java.provider.CompletionProvider(service).complete(file, contents, cursor);
        } catch (Throwable e) {
            if (e instanceof ProcessCanceledException) {
                throw e;
            }
            if (BuildConfig.DEBUG) {
                Log.e("JavaCompletionProvider", "Unable to get completions", e);
            }
            compilerProvider.destroy();
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


    private boolean isIncrementalCompletion(CachedCompletion cachedCompletion, File file,
                                            String prefix, int line, int column) {
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

        if (prefix.length() - cachedCompletion.getPrefix().length() != column - cachedCompletion.getColumn()) {
            return false;
        }

        return true;
    }
}
