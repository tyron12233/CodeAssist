package com.tyron.completion.impl;

import android.os.Build;

import androidx.annotation.RequiresApi;

import com.tyron.completion.CompletionContributor;
import com.tyron.completion.CompletionInitializationContext;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProcess;
import com.tyron.completion.CompletionType;
import com.tyron.completion.EditorMemory;
import com.tyron.completion.OffsetMap;
import com.tyron.editor.Editor;
import com.tyron.legacyEditor.Caret;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.WriteAction;
import org.jetbrains.kotlin.com.intellij.openapi.command.WriteCommandAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiFileEx;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.reference.SoftReference;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;

public class CompletionInitializationUtil {

    private static final Logger LOG = Logger.getInstance(CompletionInitializationUtil.class);

    public static CompletionInitializationContext createCompletionInitializationContext(@NotNull Project project,
                                                                                        @NotNull Editor editor,
                                                                                        @NotNull Caret caret,
                                                                                        int invocationCount,
                                                                                        CompletionType completionType) {
        return WriteCommandAction.runWriteCommandAction(project,
                (Computable<CompletionInitializationContext>) () -> {
                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                    CompletionAssertions.checkEditorValid(editor);

                    final PsiFile psiFile = editor.getUserData(EditorMemory.FILE_KEY);
                                assert psiFile != null : "no PSI file: " + FileDocumentManager
                                .getInstance().getFile(editor.getDocument());
                    psiFile.putUserData(PsiFileEx.BATCH_REFERENCE_PROCESSING, Boolean.TRUE);
                    CompletionAssertions.assertCommitSuccessful(editor, psiFile);

                    return runContributorsBeforeCompletion(editor,
                            psiFile,
                            invocationCount,
                            caret,
                            completionType);
                });
    }

    public static CompletionInitializationContext runContributorsBeforeCompletion(Editor editor,
                                                                                  PsiFile psiFile,
                                                                                  int invocationCount,
                                                                                  @NotNull Caret caret,
                                                                                  CompletionType completionType) {
        final Ref<CompletionContributor> current = Ref.create(null);
        CompletionInitializationContext context = new CompletionInitializationContext(editor,
                caret,
                psiFile.getLanguage(),
                psiFile,
                completionType,
                invocationCount);
        Project project = psiFile.getProject();
        for (final CompletionContributor contributor :
                CompletionContributor.forLanguageHonorDumbness(
                context.getPositionLanguage(),
                project)) {
            current.set(contributor);
            contributor.beforeCompletion(context);
            CompletionAssertions.checkEditorValid(editor);
//            assert !PsiDocumentManager.getInstance(project)
//                    .isUncommited(editor.getDocument()) : "Contributor " +
//                                                           contributor +
//                                                           " left the document uncommitted";
        }
        return context;
    }

    @NotNull
    public static CompletionParameters createCompletionParameters(CompletionInitializationContext initContext,
                                                                  CompletionProcess indicator,
                                                                  OffsetsInFile finalOffsets) {
        int offset =
                finalOffsets.getOffsets().getOffset(CompletionInitializationContext.START_OFFSET);
        PsiFile fileCopy = finalOffsets.getFile();
        PsiFile originalFile = fileCopy.getOriginalFile();
        PsiElement insertedElement = findCompletionPositionLeaf(finalOffsets, offset, originalFile);
        insertedElement.putUserData(CompletionContext.COMPLETION_CONTEXT_KEY, new
        CompletionContext(fileCopy, finalOffsets.getOffsets()));

        initContext.getEditor().putUserData(EditorMemory.INSERTED_KEY, insertedElement);

        return new CompletionParameters(insertedElement,
                originalFile,
                initContext.getCompletionType(),
                offset,
                initContext.getInvocationCount(),
                initContext.getEditor(),
                indicator);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static Supplier<? extends OffsetsInFile> insertDummyIdentifier(CompletionInitializationContext initContext, OffsetsInFile hostOffsets, Disposable disposable) {
        final Consumer<Supplier<Disposable>> registerDisposable = supplier -> Disposer.register(disposable, supplier.get());

        return doInsertDummyIdentifier(initContext, hostOffsets, false, registerDisposable);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private static Supplier<? extends OffsetsInFile> doInsertDummyIdentifier(
            CompletionInitializationContext initContext,
            OffsetsInFile topLevelOffsets,
            boolean noWriteLock,
            Consumer<? super Supplier<Disposable>> registerDisposable) {

        CompletionAssertions.checkEditorValid(initContext.getEditor());
        if (initContext.getDummyIdentifier().isEmpty()) {
            return () -> topLevelOffsets;
        }

        Editor editor = initContext.getEditor();
        Document originalDocument = editor.getDocument();
//        Editor hostEditor = editor instanceof EditorWindow ? ((EditorWindow)editor).getDelegate
//        () : editor;
        OffsetMap hostMap = topLevelOffsets.getOffsets();

        PsiFile hostCopy = obtainFileCopy(topLevelOffsets.getFile(), noWriteLock);
        Document copyDocument = Objects.requireNonNull(hostCopy.getViewProvider().getDocument());

        String dummyIdentifier = initContext.getDummyIdentifier();
        int startOffset = hostMap.getOffset(CompletionInitializationContext.START_OFFSET);
        int endOffset = hostMap.getOffset(CompletionInitializationContext.SELECTION_END_OFFSET);

        Supplier<OffsetsInFile> apply =
                topLevelOffsets.replaceInCopy(hostCopy, startOffset, endOffset, dummyIdentifier);


        // despite being non-physical, the copy file should only be modified in a write action,
        // because it's reused in multiple completions and it can also escapes uncontrollably
        // into other threads (e.g. quick doc)

        //kskrygan: this check is non-relevant for CWM (quick doc and other features work
        // separately)
        //and we are trying to avoid useless write locks during completion
        return skipWriteLockIfNeeded(noWriteLock, () -> {
            registerDisposable.accept((Supplier<Disposable>) () -> new OffsetTranslator(
                    originalDocument,
                    initContext.getFile(),
                    copyDocument,
                    startOffset,
                    endOffset,
                    dummyIdentifier));
            OffsetsInFile copyOffsets = apply.get();

            registerDisposable.accept((Supplier<Disposable>) copyOffsets::getOffsets);

            return copyOffsets;
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private static Supplier<? extends OffsetsInFile> skipWriteLockIfNeeded(boolean skipWriteLock,
                                                                           Supplier<?
                                                                                   extends OffsetsInFile> toWrap) {
        if (skipWriteLock) {
            return toWrap;
        } else {
            return (Supplier<OffsetsInFile>) () -> {
                CompletableFuture<OffsetsInFile> future = new CompletableFuture<>();
                WriteAction.run(() -> {
                    future.complete(toWrap.get());
                });
                return future.join();
            };

        }
    }

    private static final Key<SoftReference<Pair<PsiFile, Document>>> FILE_COPY_KEY =
            Key.create("CompletionFileCopy");

    private static PsiFile obtainFileCopy(PsiFile file, boolean forbidCaching) {
        final VirtualFile virtualFile = file.getVirtualFile();
        boolean mayCacheCopy = !forbidCaching &&
                               file.isPhysical() &&
                               // we don't want to cache code fragment copies even if they appear
                               // to be physical
                               virtualFile != null &&
                               virtualFile.isInLocalFileSystem();
        if (mayCacheCopy) {
            final Pair<PsiFile, Document> cached =
                    SoftReference.dereference(file.getUserData(FILE_COPY_KEY));
            if (cached != null && isCopyUpToDate(cached.second, cached.first, file)) {
                PsiFile copy = cached.first;
                CompletionAssertions.assertCorrectOriginalFile("Cached", file, copy);
                return copy;
            }
        }

        final PsiFile copy = (PsiFile) file.copy();
        if (copy.isPhysical() || copy.getViewProvider().isEventSystemEnabled()) {
            LOG.error("File copy should be non-physical and non-event-system-enabled! Language=" +
                      file.getLanguage() +
                      "; file=" +
                      file +
                      " of " +
                      file.getClass());
        }
        CompletionAssertions.assertCorrectOriginalFile("New", file, copy);

        if (mayCacheCopy) {
            final Document document = copy.getViewProvider().getDocument();
            assert document != null;
            syncAcceptSlashR(file.getViewProvider().getDocument(), document);
            file.putUserData(FILE_COPY_KEY, new SoftReference<>(Pair.create(copy, document)));
        }
        return copy;
    }

    @NotNull
    private static PsiElement findCompletionPositionLeaf(OffsetsInFile offsets,
                                                         int offset,
                                                         PsiFile originalFile) {
        PsiElement insertedElement = offsets.getFile().findElementAt(offset);
        if (insertedElement == null && offsets.getFile().getTextLength() == offset) {
            insertedElement = PsiTreeUtil.getDeepestLast(offsets.getFile());
        }
        CompletionAssertions.assertCompletionPositionPsiConsistent(offsets,
                offset,
                originalFile,
                insertedElement);
        return insertedElement;
    }

    private static boolean isCopyUpToDate(Document document,
                                          @NotNull PsiFile copyFile,
                                          @NotNull PsiFile originalFile) {
        if (!copyFile.getClass().equals(originalFile.getClass()) ||
            !copyFile.isValid() ||
            !copyFile.getName().equals(originalFile.getName())) {
            return false;
        }
        // the psi file cache might have been cleared by some external activity,
        // in which case PSI-document sync may stop working
        PsiFile current =
                PsiDocumentManager.getInstance(copyFile.getProject()).getPsiFile(document);
        return current != null &&
               current.getViewProvider().getPsi(copyFile.getLanguage()) == copyFile;
    }

    private static void syncAcceptSlashR(Document originalDocument, Document documentCopy) {
        if (!(originalDocument instanceof DocumentImpl) ||
            !(documentCopy instanceof DocumentImpl)) {
            return;
        }

        ((DocumentImpl) documentCopy).setAcceptSlashR(((DocumentImpl) originalDocument).acceptsSlashR());
    }
}
