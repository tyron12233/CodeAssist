package com.tyron.completion.psi.completion;

import androidx.annotation.NonNull;

import com.tyron.completion.InsertHandler;
import com.tyron.completion.InsertionContext;
import com.tyron.completion.java.util.JavaCompletionUtil;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.completion.psi.completion.item.JavaPsiClassReferenceElement;
import com.tyron.editor.Editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.codeInsight.CodeInsightUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiCompiledElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

public class AllClassesGetter {


    public static final InsertHandler<JavaPsiClassReferenceElement> TRY_SHORTENING = new InsertHandler<JavaPsiClassReferenceElement>() {

        private void _handleInsert(final InsertionContext context, final JavaPsiClassReferenceElement item) {
            final Editor editor = context.getEditor();
            final PsiClass psiClass = item.getObject();

            if (!psiClass.isValid()) return;

            int endOffset = editor.getCaret().getStart();
            final String qname = psiClass.getQualifiedName();
            if (qname == null) return;

            if (endOffset == 0) return;

            Document document = editor.getDocument();
            final PsiFile file = context.getFile();
            PsiReference psiReference = file.findReferenceAt(endOffset - 1);


            boolean insertFqn = true;
            if (psiReference != null) {
                final PsiManager psiManager = file.getManager();
                if (psiManager.areElementsEquivalent(psiClass, JavaCompletionUtil.resolveReference(psiReference))) {
                    insertFqn = false;
                } else if (psiClass.isValid()) {
                    try {
                        context
                                .setTailOffset(psiReference.getRangeInElement().getEndOffset() + psiReference.getElement().getTextRange().getStartOffset());
                        final PsiElement newUnderlying = psiReference.bindToElement(psiClass);
                        if (newUnderlying != null) {
                            for (final PsiReference reference : newUnderlying.getReferences()) {
                                if (psiManager.areElementsEquivalent(psiClass, JavaCompletionUtil.resolveReference(reference))) {
                                    insertFqn = false;
                                    break;
                                }
                            }
                        }
                    }
                    catch (IncorrectOperationException e) {
                        //if it's empty we just insert fqn below
                    }
                }
            }

//            if (toDelete != null && toDelete.isValid()) {
//                document.deleteString(toDelete.getStartOffset(), toDelete.getEndOffset());
//                context.setTailOffset(toDelete.getStartOffset());
//            }
//
//            if (insertFqn) {
//                INSERT_FQN.handleInsert(context, item);
//            }
        }

        @Override
        public void handleInsert(@NonNull InsertionContext context, @NonNull JavaPsiClassReferenceElement item) {
            _handleInsert(context, item);
            item.getTailType().processTail(context.getEditor(), context.getEditor().getCaret().getStart());
        }
    };

//    public static boolean isAcceptableInContext(@NotNull final PsiElement context,
//                                                @NotNull final PsiClass psiClass,
//                                                final boolean filterByScope, final boolean pkgContext) {
//        ProgressManager.checkCanceled();
//
//        if (JavaCompletionUtil.isInExcludedPackage(psiClass, false)) return false;
//
//        final String qualifiedName = psiClass.getQualifiedName();
//        if (qualifiedName == null) return false;
//
//        if (!filterByScope && !(psiClass instanceof PsiCompiledElement)) return true;
//
//        return JavaCompletionUtil.isSourceLevelAccessible(context, psiClass, pkgContext);
//    }

    public static JavaPsiClassReferenceElement createLookupItem(@NotNull final PsiClass psiClass,
                                                                final InsertHandler<JavaPsiClassReferenceElement> insertHandler) {
        final JavaPsiClassReferenceElement item = new JavaPsiClassReferenceElement(psiClass);
        item.setInsertHandler(insertHandler);
        return item;
    }
}
