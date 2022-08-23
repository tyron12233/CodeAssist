package com.tyron.completion.java;

import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import android.util.Log;

import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.parser.JavacParser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.compiler.services.NBEnter;
import com.tyron.completion.java.compiler.services.NBJavaCompiler;
import com.tyron.completion.java.compiler.services.NBLog;
import com.tyron.completion.java.compiler.services.NBParserFactory;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.completion.java.provider.Completions;
import com.tyron.completion.java.provider.DefaultJavacUtilitiesProvider;
import com.tyron.completion.java.provider.IdentifierCompletionProvider;
import com.tyron.completion.java.provider.JavacUtilitiesProvider;
import com.tyron.completion.java.provider.MemberReferenceCompletionProvider;
import com.tyron.completion.java.provider.MemberSelectCompletionProvider;
import com.tyron.completion.java.provider.VariableNameCompletionProvider;
import com.tyron.completion.java.util.FileContentFixer;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProcessCanceledException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;

import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

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

            // if the cached completion is incomplete,
            // chances are there will be new items that are not in the cache
            // so don't return the cached items
            if (!copy.isIncomplete && !copy.items.isEmpty()) {
                return copy;
            }
        }

        CompletionList.Builder complete = completeV2(params);
        if (complete == null) {
            return CompletionList.EMPTY;
        }
        CompletionList list = complete.build();

        String newPrefix = params.getPrefix();
        if (params.getPrefix().contains(".")) {
            newPrefix = partialIdentifier(params.getPrefix(), params.getPrefix().length());
        }

        mCachedCompletion =
                new CachedCompletion(params.getFile(), params.getLine(), params.getColumn(),
                        newPrefix, list);
        return list;
    }

    public CompletionList.Builder completeV2(CompletionParameters parameters) {
        CompilationInfo compilationInfo =
                parameters.getModule().getUserData(CompilationInfo.COMPILATION_INFO_KEY);
        if (compilationInfo == null) {
            return null;
        }

        JavacTaskImpl javacTask = compilationInfo.impl.getJavacTask();
        Context context = javacTask.getContext();
        SimpleJavaFileObject fileObject = new SimpleJavaFileObject(parameters.getFile().toURI(), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return new FileContentFixer(context).fixFileContent(parameters.getContents());
                    }
                };

        JCTree.JCCompilationUnit unit = compilationInfo.updateImmediately(fileObject);
        if (unit == null) {
            return null;
        }
        JavacUtilitiesProvider javacUtilities = new DefaultJavacUtilitiesProvider(javacTask, unit, parameters.getProject());
        TreePath scanned = new FindCurrentPath(javacTask).scan(unit, parameters.getIndex());
        if (scanned == null || scanned.getLeaf() == null) {
            return null;
        }
        CompletionList.Builder builder = CompletionList.builder(parameters.getPrefix());

        switch (scanned.getLeaf().getKind()) {
            case IDENTIFIER:
                new IdentifierCompletionProvider(null).complete(builder, javacUtilities,
                        scanned, parameters.getPrefix(), false);
                break;
            case MEMBER_SELECT:
                new MemberSelectCompletionProvider(null).complete(builder, javacUtilities,
                        scanned, parameters.getPrefix(), false);
                break;
            case MEMBER_REFERENCE:
                new MemberReferenceCompletionProvider(null).complete(builder, javacUtilities,
                        scanned, parameters.getPrefix(), false);
            case VARIABLE:
                new VariableNameCompletionProvider(null).complete(builder, javacUtilities,
                        scanned, parameters.getPrefix(), false);
                break;
        }
        return builder;
    }


    public CompletionList.Builder complete(Project project,
                                           JavaModule module,
                                           File file,
                                           String contents,
                                           long cursor) {
        JavaCompilerProvider compilerProvider =
                CompilerService.getInstance().getIndex(JavaCompilerProvider.KEY);
        JavaCompilerService service = compilerProvider.getCompiler(project, module);

        try {
            return new Completions(service).complete(file, contents, cursor);
        } catch (Throwable e) {
            if (e instanceof ProcessCanceledException) {
                service.invalidate(file.toPath());
                throw e;
            }
            if (BuildConfig.DEBUG) {
                Log.e("JavaCompletionProvider", "Unable to get completions", e);
            }
            service.destroy();
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

        return prefix.length() - cachedCompletion.getPrefix().length() ==
               column - cachedCompletion.getColumn();
    }
}
