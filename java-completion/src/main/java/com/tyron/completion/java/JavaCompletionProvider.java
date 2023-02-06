package com.tyron.completion.java;

import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.google.common.base.Throwables;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.java.provider.JavaKotlincCompletionProvider;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionList;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.project.IndexNotReadyException;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.ReferenceRange;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;

import java.io.File;

public class JavaCompletionProvider extends CompletionProvider {

    private CachedCompletion mCachedCompletion;

    @SuppressWarnings("ALL")
    public JavaCompletionProvider() {

    }

    @Override
    public boolean accept(File file) {
        return file.isFile() && file.getName().endsWith(".java");
    }

    @Override
    public CompletionList complete(CompletionParameters params) {
        throw new UnsupportedOperationException();
    }

    public void completeV2(CompletionParameters parameters) {
        JavaKotlincCompletionProvider javaKotlincCompletionProvider =
                new JavaKotlincCompletionProvider();

        try {
            Class<?> aClass =
                    Class.forName("com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeEditorFacade");
            JavaCoreProjectEnvironment projectEnvironment =
                    (JavaCoreProjectEnvironment) aClass.getDeclaredField("projectEnvironment")
                            .get(null);

            PsiElement psiElement = ReadAction.compute(() -> {
                if (projectEnvironment == null) {
                    return null;
                }
                CoreLocalFileSystem localFileSystem =
                        projectEnvironment.getEnvironment().getLocalFileSystem();
                VirtualFile virtualFile = localFileSystem.findFileByIoFile(parameters.getFile());

                if (virtualFile == null) {
                    return null;
                }

                PsiManager psiManager = PsiManager.getInstance(projectEnvironment.getProject());
                PsiFile file = psiManager.findFile(virtualFile);

                if (file == null || !file.isValid()) {
                    return null;
                }

                file = (PsiFile) file.copy();

                Document document = file.getViewProvider().getDocument();
                assert document != null;

                CommandProcessor.getInstance()
                        .executeCommand(projectEnvironment.getProject(), () -> {
                            document.insertString((int) (parameters.getIndex()),
                                    "IntelijIdeaRulezzzzzzzz");
                        }, "Insert fake identifier", null);

                ((PsiFileImpl) file).onContentReload();

                return file.findElementAt((int) parameters.getIndex());
            });

            if (psiElement == null) {
                return;
            }

            final long offset = parameters.getIndex();
            TextRange range = psiElement.getTextRange();
            assert range.containsOffset((int) offset) : psiElement +
                                                        "; " +
                                                        offset +
                                                        " not in " +
                                                        range;

            final Document document =
                    psiElement.getContainingFile().getViewProvider().getDocument();
            String prefix = findReferencePrefix(psiElement, ((int) offset));
            if (prefix == null && !parameters.getPrefix().isEmpty()) {
                prefix = parameters.getPrefix();
            }

            if (prefix == null) {
                return;
            }

            // only allow completions if prefix is not empty
            // and the parent is a reference expression (this.[cursor])
            if (prefix.isEmpty() && !(psiElement.getParent() instanceof PsiReferenceExpression)) {
                return;
            }

            CompletionList.Builder builder = parameters.getBuilder();
            builder.setCompletionPrefix(prefix);
            javaKotlincCompletionProvider.fillCompletionVariants(psiElement, builder);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @Nullable
    public static String findReferencePrefix(@NotNull PsiElement position, int offsetInFile) {
        try {
            PsiUtilCore.ensureValid(position);
            PsiReference ref = position.getContainingFile().findReferenceAt(offsetInFile);
            if (ref != null) {
                PsiElement element = ref.getElement();
                int offsetInElement = offsetInFile - element.getTextRange().getStartOffset();
                for (TextRange refRange : ReferenceRange.getRanges(ref)) {
                    if (refRange.contains(offsetInElement)) {
                        int beginIndex = refRange.getStartOffset();
                        String text = element.getText();
                        if (beginIndex < 0 ||
                            beginIndex > offsetInElement ||
                            offsetInElement > text.length()) {
                            throw new AssertionError("Inconsistent reference range:" +
                                                     " ref=" +
                                                     ref.getClass() +
                                                     " element=" +
                                                     element.getClass() +
                                                     " ref.start=" +
                                                     refRange.getStartOffset() +
                                                     " offset=" +
                                                     offsetInElement +
                                                     " psi.length=" +
                                                     text.length());
                        }
                        return text.substring(beginIndex, offsetInElement);
                    }
                }
            }
        } catch (IndexNotReadyException ignored) {
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
