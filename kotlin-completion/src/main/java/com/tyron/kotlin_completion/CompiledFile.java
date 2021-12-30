package com.tyron.kotlin_completion;

import com.tyron.completion.progress.ProgressManager;
import com.tyron.kotlin_completion.compiler.CompletionKind;
import com.tyron.kotlin_completion.position.Position;
import com.tyron.kotlin_completion.util.PsiUtils;

import org.jetbrains.kotlin.com.google.common.collect.ImmutableMap;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.container.ComponentProvider;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtElement;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtReferenceExpression;
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.scopes.LexicalScope;
import org.jetbrains.kotlin.types.KotlinType;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import kotlin.collections.MapsKt;
import kotlin.sequences.Sequence;
import kotlin.sequences.SequencesKt;
import kotlin.text.StringsKt;

public class CompiledFile {

    private final String mContent;
    private final KtFile mParse;
    private final BindingContext mCompile;
    private final ComponentProvider mContainer;
    private final Collection<KtFile> mSourcePath;
    private final CompilerClassPath mClassPath;
    private final CompletionKind mKind = CompletionKind.DEFAULT;

    public CompiledFile(String mContent, KtFile mParse, BindingContext mCompile, ComponentProvider mContainer, Collection<KtFile> mSourcePath, CompilerClassPath classpath) {
        this.mContent = mContent;
        this.mParse = mParse;
        this.mCompile = mCompile;
        this.mContainer = mContainer;
        this.mClassPath = classpath;
        this.mSourcePath = mSourcePath;
    }

    public KotlinType typeAtPoint(int cursor) {
        KtElement cursorExpr = parseAtPoint(cursor, true);
        if (cursorExpr == null) {
            return null;
        }
        cursorExpr = PsiUtils.findParent(cursorExpr, KtExpression.class);
        if (cursorExpr == null) {
            return null;
        }
        KtElement surroundingExpr = expandForType(cursor, (KtExpression) cursorExpr);
        LexicalScope scope = scopeAtPoint(cursor);
        if (scope == null) {
            return null;
        }

        return typeOfExpression((KtExpression) surroundingExpr, scope);
    }

    public KotlinType typeOfExpression(KtExpression expression, LexicalScope scopeWithImports) {
        return bindingContextOf(expression, scopeWithImports).getType(expression);
    }

    public BindingContext bindingContextOf(KtExpression expression, LexicalScope scopeWithImports) {
        return mClassPath.getCompiler().compileKtExpression(expression, scopeWithImports, mSourcePath).getFirst();
    }

    public Pair<KtExpression, DeclarationDescriptor> referenceAtPoint(int cursor) {
        ProgressManager.checkCanceled();
        KtElement element = parseAtPoint(cursor, true);
        if (element == null) {
            return null;
        }

        KtExpression cursorExpr = PsiUtils.findParent(element, KtExpression.class);
        if (cursorExpr == null) {
            return null;
        }
        KtExpression surroundingExpr = expandForReference(cursor, cursorExpr);
        LexicalScope scope = scopeAtPoint(cursor);
        if (scope == null) {
            return null;
        }
        BindingContext context = bindingContextOf(surroundingExpr, scope);
        return referenceFromContext(cursor, context);
    }

    public Pair<KtExpression, DeclarationDescriptor> referenceFromContext(int cursor, BindingContext context) {
        ProgressManager.checkCanceled();
        ImmutableMap<KtReferenceExpression, DeclarationDescriptor> targets = context.getSliceContents(BindingContext.REFERENCE_TARGET);
        Sequence<Map.Entry<KtReferenceExpression, DeclarationDescriptor>> filter = SequencesKt.filter(MapsKt.asSequence(targets), it -> it.getKey().getTextRange().contains(cursor));
        Sequence<Map.Entry<KtReferenceExpression, DeclarationDescriptor>> sorted = SequencesKt.sortedBy(filter, it -> it.getKey().getTextRange().getLength());
        Sequence<Pair<KtExpression, DeclarationDescriptor>> map = SequencesKt.map(sorted, it -> Pair.create(it.getKey(), it.getValue()));
        return SequencesKt.firstOrNull(map);
    }

    public KtExpression expandForReference(int cursor, KtExpression surroundingExpr) {
        PsiElement parent = surroundingExpr.getParent();

        if (parent instanceof KtDotQualifiedExpression || parent instanceof KtSafeQualifiedExpression || parent instanceof KtCallExpression) {
            KtExpression ktExpression = expandForReference(cursor, (KtExpression) parent);
            if (ktExpression != null) {
                return ktExpression;
            }
        }
        return surroundingExpr;
    }


    private KtExpression expandForType(int cursor, KtExpression surroundingExpr) {
        KtDotQualifiedExpression dotParent = (KtDotQualifiedExpression) surroundingExpr.getParent();
        if (dotParent != null && dotParent.getSelectorExpression().getTextRange().contains(cursor)) {
            return expandForType(cursor, dotParent);
        }
        return surroundingExpr;
    }

    public KtElement parseAtPoint(int cursor, boolean asReference) {
        return CompiledFileUtilKt.parseAtPoint(mClassPath, cursor, oldOffset(cursor), mContent, mParse, asReference);
    }

    public String lineBefore(int cursor) {
        return StringsKt.substringAfterLast(mContent.substring(0, cursor), '\n', "\n");
    }

    public String lineAfter(int cursor) {
        return StringsKt.substringBefore(mContent.substring(cursor), "\n", "\n");
    }


    public LexicalScope scopeAtPoint(int cursor) {
        int oldCursor = oldOffset(cursor);
        Sequence<Map.Entry<KtElement, LexicalScope>> sequence = MapsKt.asSequence(mCompile.getSliceContents(BindingContext.LEXICAL_SCOPE));
        Sequence<Map.Entry<KtElement, LexicalScope>> filter = SequencesKt.filter(sequence, it -> (it.getKey().getTextRange().getStartOffset() <= oldCursor && oldCursor <= it.getKey().getTextRange().getEndOffset()));
        Sequence<Map.Entry<KtElement, LexicalScope>> entrySequence = SequencesKt.sortedBy(filter, it -> it.getKey().getTextRange().getLength());
        Sequence<LexicalScope> map = SequencesKt.map(entrySequence, Map.Entry::getValue);
        return SequencesKt.firstOrNull(map);
    }

    public KtElement elementAtPoint(int cursor) {
        int oldCursor = oldOffset(cursor);
        PsiElement psi = mParse.findElementAt(oldCursor);
        return PsiUtils.findParent(psi, KtElement.class);
    }

    private int oldOffset(int cursor) {
        Pair<TextRange, TextRange> pair = Position.changedRegion(mParse.getText(), mContent);

        if (pair == null || pair.getFirst() == null || pair.getSecond() == null) {
            return cursor;
        }

        TextRange oldChanged = pair.getFirst();
        TextRange newChanged = pair.getSecond();

        if (cursor <= newChanged.getStartOffset()) {
            return cursor;
        }
        if (cursor < newChanged.getEndOffset()) {
            int newRelative = cursor - newChanged.getStartOffset();
            int oldRelative = newRelative * oldChanged.getLength() / newChanged.getLength();
            return oldChanged.getStartOffset() + oldRelative;
        }

        return mParse.getText().length() - (mContent.length() - cursor);
    }

    public String describeRange(TextRange range, boolean oldContent) {
        try {
            String c = oldContent ? mParse.getText() : mContent;
            com.tyron.completion.model.Position start = Position.position(c, range.getStartOffset());
            com.tyron.completion.model.Position end = Position.position(c, range.getEndOffset());
            String file = mParse.getVirtualFile().getName();

            return file + " " + start.line + ":" + (start.column + 1) + "-" + (end.line + 1) + ":" + (end.column + 1);
        } catch (IOException e) {
            return "Unknown range: " + range;
        }
    }

    public ComponentProvider getContainer() {
        return mContainer;
    }

    public BindingContext getCompile() {
        return mCompile;
    }

    public KtFile getParse() {
        return mParse;
    }
}
