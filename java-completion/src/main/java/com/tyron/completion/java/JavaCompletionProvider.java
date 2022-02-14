package com.tyron.completion.java;

import static com.tyron.completion.progress.ProgressManager.checkCanceled;

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
        checkCanceled();

        if (isIncrementalCompletion(mCachedCompletion, params)) {
            String partial = partialIdentifier(params.getPrefix(), params.getPrefix().length());
            CompletionList cachedList = mCachedCompletion.getCompletionList();
            CompletionList copy = CompletionList.copy(cachedList, partial);
            if (!copy.isIncomplete || !copy.items.isEmpty()) {
                return copy;
            }
        }

        CompletionList.Builder complete = complete(params.getProject(), (JavaModule) params.getModule(),
                params.getFile(), params.getContents(), params.getIndex());
        if (complete == null) {
            return CompletionList.EMPTY;
        }
        CompletionList list = complete.build();

        String newPrefix = params.getPrefix();
        if (params.getPrefix().contains(".")) {
            newPrefix = partialIdentifier(params.getPrefix(), params.getPrefix().length());
        }

        mCachedCompletion = new CachedCompletion(params.getFile(), params.getLine(),
                params.getColumn(), newPrefix, list);
        return list;
    }

    public CompletionList.Builder complete(
            Project project, JavaModule module, File file, String contents, long cursor) {
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
        }
        return null;
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
        return FuzzySearch.ratio(label, partialIdentifier);
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
