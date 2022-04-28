package com.tyron.completion.java;

import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import android.util.Log;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.KotlinModule;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.provider.Completions;
import com.tyron.completion.java.provider.JavaKotlincCompletionProvider;
import com.tyron.completion.java.util.CompletionItemFactory;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.kotlin.completion.core.model.KotlinEnvironment;

import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.DocumentEx;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.event.DocumentEventImpl;
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.impl.CoreProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.AbstractProgressIndicatorBase;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.psi.FileViewProvider;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiDirectory;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.SymbolCollectingProcessor;
import org.jetbrains.kotlin.com.intellij.psi.scope.util.PsiScopesUtil;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubTreeLoader;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.containers.MostlySingularMultiMap;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class JavaCompletionProvider extends CompletionProvider {

    private static final String COMPLETION_THREAD_CLASS = "io.github.rosemoe.sora.widget.component.EditorAutoCompletion$CompletionThread";
    private static final Field sCanceledField;

    static {
        try {
            Class<?> completionThreadClass = Class.forName(COMPLETION_THREAD_CLASS);
            sCanceledField = completionThreadClass.getDeclaredField("mAborted");
            sCanceledField.setAccessible(true);
        } catch (Throwable e) {
            throw new Error("Rosemoe thread implementation has changed", e);
        }
    }

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

    public CompletionList.Builder completeWithKotlinc(
            Project project, JavaModule module, File file, String contents, long cursor) {
        if (!(module instanceof KotlinModule)) {
            // should not happen as all android modules are kotlin module
            throw new RuntimeException("Not a kotlin module");
        }
        KotlinModule kotlinModule = ((KotlinModule) module);
        KotlinCoreEnvironment environment = KotlinEnvironment.getEnvironment(kotlinModule);
        org.jetbrains.kotlin.com.intellij.openapi.project.Project jetProject =
                environment.getProject();

        setCurrentIndicator();

        VirtualFile virtualFile =
                environment.getProjectEnvironment().getEnvironment().getLocalFileSystem()
                        .findFileByIoFile(file);
        assert virtualFile != null && virtualFile.isValid();

        PsiFile storedPsi = PsiManager.getInstance(jetProject).findFile(virtualFile);
        assert storedPsi != null && storedPsi.isValid();

        String oldText = storedPsi.getText();

        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(jetProject);
        Document document = documentManager.getDocument(storedPsi);
        assert document != null;

        document.setText(contents);

        DocumentEventImpl event = new DocumentEventImpl(document, 0, oldText, contents, storedPsi.getModificationStamp(), true);
        ((PsiDocumentManagerBase) documentManager).documentChanged(event);

        PsiFileFactory factory = PsiFileFactory.getInstance(jetProject);
        PsiFile parsed =
                factory.createFileFromText(contents, storedPsi);
        assert parsed != null;

        storedPsi.getViewProvider().rootChanged(parsed);

        PsiElement elementAt = parsed.findElementAt((int) (cursor - 1));
        assert elementAt != null;

        CompletionList.Builder builder = new CompletionList.Builder(elementAt.getText());
        JavaKotlincCompletionProvider provider =
                new JavaKotlincCompletionProvider(module);
        provider.fillCompletionVariants(elementAt, builder);
        return builder;
    }

    private void setCurrentIndicator() {
        try {
            ProgressIndicator indicator = new AbstractProgressIndicatorBase() {
                @Override
                public void checkCanceled() {
                    ProgressManager.checkCanceled();
                    if (Thread.currentThread().getClass().getName().equals(COMPLETION_THREAD_CLASS)) {
                        try {
                            Object o = sCanceledField.get(Thread.currentThread());
                            if (o instanceof Boolean) {
                                if (((Boolean) o)) {
                                    cancel();
                                }
                            }
                        } catch (IllegalAccessException e) {
                            // ignored
                        }
                    }
                    super.checkCanceled();
                }
            };

            // the kotlin compiler does not provide implementations to run a progress indicator,
            // since the PSI infrastructure calls ProgressManager#checkCancelled, we need this
            // hack to make thread cancellation work
            Method setCurrentIndicator = CoreProgressManager.class
                    .getDeclaredMethod("setCurrentIndicator", long.class, ProgressIndicator.class);
            setCurrentIndicator.setAccessible(true);
            setCurrentIndicator.invoke(null, Thread.currentThread().getId(), indicator);
        } catch (Throwable e) {
            throw new Error(e);
        }
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
