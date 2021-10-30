package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.InsertHandler;
import com.tyron.psi.completion.InsertionContext;
import com.tyron.psi.completion.OffsetKey;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.completions.lang.java.search.AllClassesSearchExecutor;
import com.tyron.psi.editor.Editor;
import com.tyron.psi.util.DocumentUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.codeInsight.CodeInsightUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.RangeMarker;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiCompiledElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;
import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public final class AllClassesGetter {
    private static final Logger LOG = Logger.getInstance(AllClassesGetter.class);
    public static final InsertHandler<JavaPsiClassReferenceElement> TRY_SHORTENING = new InsertHandler<JavaPsiClassReferenceElement>() {

        private void _handleInsert(final InsertionContext context, final JavaPsiClassReferenceElement item) {
            final Editor editor = context.getEditor();
            final PsiClass psiClass = item.getObject();
            if (!psiClass.isValid()) return;

            int endOffset = editor.getCaretModel().getOffset();
            final String qname = psiClass.getQualifiedName();
            if (qname == null) return;

            if (endOffset == 0) return;

            final Document document = editor.getDocument();
            final PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(psiClass.getProject());
            final PsiFile file = context.getFile();
            if (file.findElementAt(endOffset - 1) == null) return;

            final OffsetKey key = OffsetKey.create("endOffset", false);
            context.getOffsetMap().addOffset(key, endOffset);
            //PostprocessReformattingAspect.getInstance(context.getProject()).doPostponedFormatting();

            final int newOffset = context.getOffsetMap().getOffset(key);
            if (newOffset >= 0) {
                endOffset = newOffset;
            }
            else {
                LOG.error(endOffset + " became invalid: " + context.getOffsetMap() + "; inserting " + qname);
            }

            final RangeMarker toDelete = JavaCompletionUtil.insertTemporary(endOffset, document, " ");
            psiDocumentManager.commitAllDocuments();
            PsiReference psiReference = file.findReferenceAt(endOffset - 1);

            boolean insertFqn = true;
            if (psiReference != null) {
                final PsiManager psiManager = file.getManager();
                if (psiManager.areElementsEquivalent(psiClass, JavaCompletionUtil.resolveReference(psiReference))) {
                    insertFqn = false;
                }
                else if (psiClass.isValid()) {
                    try {
                        context
                                .setTailOffset(psiReference.getRangeInElement().getEndOffset() + psiReference.getElement().getTextRange().getStartOffset());
                        final PsiElement newUnderlying = psiReference.bindToElement(psiClass);
                        if (newUnderlying != null) {
                            final PsiElement psiElement = newUnderlying; //CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(newUnderlying);
                            if (psiElement != null) {
                                for (final PsiReference reference : psiElement.getReferences()) {
                                    if (psiManager.areElementsEquivalent(psiClass, JavaCompletionUtil.resolveReference(reference))) {
                                        insertFqn = false;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    catch (IncorrectOperationException e) {
                        //if it's empty we just insert fqn below
                    }
                }
            }
            if (toDelete != null && toDelete.isValid()) {
//                DocumentUtils.deleteString(document, toDelete.getStartOffset(), toDelete.getEndOffset());
//                document.deleteString(toDelete.getStartOffset(), toDelete.getEndOffset());
//                context.setTailOffset(toDelete.getStartOffset());
            }

            if (insertFqn) {
                INSERT_FQN.handleInsert(context, item);
            }
        }

        @Override
        public void handleInsert(@NotNull final InsertionContext context, @NotNull final JavaPsiClassReferenceElement item) {
            _handleInsert(context, item);
            item.getTailType().processTail(context.getEditor(), context.getEditor().getCaretModel().getOffset());
        }
    };

    public static final InsertHandler<JavaPsiClassReferenceElement> INSERT_FQN = (context, item) -> {
        final String qName = item.getQualifiedName();
        if (qName != null) {
            int start = JavaCompletionUtil.findQualifiedNameStart(context);
            context.getDocument().replaceString(start, context.getTailOffset(), qName);
            LOG.assertTrue(context.getTailOffset() >= 0);
        }
    };

    public static void processJavaClasses(@NotNull final CompletionParameters parameters,
                                          @NotNull final PrefixMatcher prefixMatcher,
                                          final boolean filterByScope,
                                          @NotNull final Consumer<? super PsiClass> consumer) {
        final PsiElement context = parameters.getPosition();
        final Project project = context.getProject();
        final GlobalSearchScope scope = filterByScope ? context.getContainingFile().getResolveScope() : GlobalSearchScope.allScope(project);

        processJavaClasses(prefixMatcher, project, scope, new LimitedAccessibleClassPreprocessor(parameters, filterByScope, c->{consumer.consume(c); return true;}));
    }

    public static void processJavaClasses(@NotNull final PrefixMatcher prefixMatcher,
                                          @NotNull Project project,
                                          @NotNull GlobalSearchScope scope,
                                          @NotNull Processor<? super PsiClass> processor) {
        final Set<String> names = new HashSet<>(10000);
        AllClassesSearchExecutor.processClassNames(project, scope, s -> {
            if (prefixMatcher.prefixMatches(s)) {
                names.add(s);
            }
            return true;
        });
        LinkedHashSet<String> sorted = prefixMatcher.sortMatching(names);
        AllClassesSearchExecutor.processClassesByNames(project, scope, sorted, processor);
    }

    public static boolean isAcceptableInContext(@NotNull final PsiElement context,
                                                @NotNull final PsiClass psiClass,
                                                final boolean filterByScope, final boolean pkgContext) {
        ProgressManager.checkCanceled();

        if (JavaCompletionUtil.isInExcludedPackage(psiClass, false)) return false;

        final String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) return false;

        if (!filterByScope && !(psiClass instanceof PsiCompiledElement)) return true;

        return JavaCompletionUtil.isSourceLevelAccessible(context, psiClass, pkgContext);
    }

    public static JavaPsiClassReferenceElement createLookupItem(@NotNull final PsiClass psiClass,
                                                                final InsertHandler<JavaPsiClassReferenceElement> insertHandler) {
        final JavaPsiClassReferenceElement item = new JavaPsiClassReferenceElement(psiClass);
        item.setInsertHandler(insertHandler);
        return item;
    }

}
