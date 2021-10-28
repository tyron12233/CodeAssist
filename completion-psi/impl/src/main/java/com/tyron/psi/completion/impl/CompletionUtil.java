package com.tyron.psi.completion.impl;

import androidx.annotation.Nullable;

import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.patterns.CharPattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.project.IndexNotReadyException;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiComment;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.ReferenceRange;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;

public class CompletionUtil {

    private static final Logger LOG = Logger.getInstance(CompletionUtil.class);

    /**
     * @return a prefix for completion matching, calculated from the given parameters.
     * The prefix is the longest substring from inside {@code parameters.getPosition()}'s text,
     * ending at {@code parameters.getOffset()}, being a valid Java identifier.
     */
    @NotNull
    public static String findJavaIdentifierPrefix(@NotNull CompletionParameters parameters) {
        return findJavaIdentifierPrefix(parameters.getPosition(), parameters.getOffset());
    }

    /**
     * @return a prefix for completion matching, calculated from the given parameters.
     * The prefix is the longest substring from inside {@code position}'s text,
     * ending at {@code offsetInFile}, being a valid Java identifier.
     */
    @NotNull
    public static String findJavaIdentifierPrefix(@Nullable PsiElement position, int offsetInFile) {
        return findIdentifierPrefix(position, offsetInFile, CharPattern.javaIdentifierPartCharacter(), CharPattern.javaIdentifierStartCharacter());
    }

    /**
     * @return a prefix for completion matching, calculated from the given element's text and the offsets.
     * The prefix is the longest substring from inside {@code position}'s text,
     * ending at {@code offsetInFile}, beginning with a character
     * satisfying {@code idStart}, and with all other characters satisfying {@code idPart}.
     */
    @NotNull
    public static String findIdentifierPrefix(@Nullable PsiElement position, int offsetInFile,
                                              @NotNull ElementPattern<Character> idPart,
                                              @NotNull ElementPattern<Character> idStart) {
        if (position == null) return "";
        int startOffset = position.getTextRange().getStartOffset();
        return findInText(offsetInFile, startOffset, idPart, idStart, position.getNode().getChars());
    }

    public static String findIdentifierPrefix(@NotNull Document document, int offset, ElementPattern<Character> idPart,
                                              ElementPattern<Character> idStart) {
        final CharSequence text = document.getImmutableCharSequence();
        return findInText(offset, 0, idPart, idStart, text);
    }

    @NotNull
    private static String findInText(int offset, int startOffset, ElementPattern<Character> idPart, ElementPattern<Character> idStart, CharSequence text) {
        final int offsetInElement = offset - startOffset;
        int start = offsetInElement - 1;
        while (start >=0) {
            if (!idPart.accepts(text.charAt(start))) break;
            --start;
        }
        while (start + 1 < offsetInElement && !idStart.accepts(text.charAt(start + 1))) {
            start++;
        }

        return text.subSequence(start + 1, offsetInElement).toString().trim();
    }



    public static String findPrefix(final PsiElement insertedElement, final int offsetInFile, ElementPattern<Character> prefixStartTrim) {
        if(insertedElement == null) return "";

        final Document document = insertedElement.getContainingFile().getViewProvider().getDocument();
        assert document != null;
        LOG.assertTrue(!PsiDocumentManager.getInstance(insertedElement.getProject()).isUncommited(document), "Uncommitted");

        final String prefix = findReferencePrefix(insertedElement, offsetInFile);
        if (prefix != null) return prefix;

        if (insertedElement.getTextRange().equals(insertedElement.getContainingFile().getTextRange()) || insertedElement instanceof PsiComment) {
            return CompletionUtil.findJavaIdentifierPrefix(insertedElement, offsetInFile);
        }

        return findPrefixDefault(insertedElement, offsetInFile, prefixStartTrim);
    }

    /**
     * @return a prefix from completion matching calculated by a reference found at parameters' offset
     * (the reference text from the beginning until that offset),
     * or {@code null} if there's no reference there.
     */
    @Nullable
    public static String findReferencePrefix(@NotNull CompletionParameters parameters) {
        return findReferencePrefix(parameters.getPosition(), parameters.getOffset());
    }

    /**
     * @return a prefix from completion matching calculated by a reference found at the given offset
     * (the reference text from the beginning until that offset),
     * or {@code null} if there's no reference there.
     */
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
                        if (beginIndex < 0 || beginIndex > offsetInElement || offsetInElement > text.length()) {
                            throw new AssertionError("Inconsistent reference range:" +
                                    " ref=" + ref.getClass() +
                                    " element=" + element.getClass() +
                                    " ref.start=" + refRange.getStartOffset() +
                                    " offset=" + offsetInElement +
                                    " psi.length=" + text.length());
                        }
                        return text.substring(beginIndex, offsetInElement);
                    }
                }
            }
        } catch (IndexNotReadyException ignored) {
        }
        return null;
    }

    public static String findPrefixDefault(final PsiElement insertedElement, final int offset, @NotNull final ElementPattern trimStart) {
        String substr = insertedElement.getText().substring(0, offset - insertedElement.getTextRange().getStartOffset());
        if (substr.length() == 0 || Character.isWhitespace(substr.charAt(substr.length() - 1))) return "";

        substr = substr.trim();

        int i = 0;
        while (substr.length() > i && trimStart.accepts(substr.charAt(i))) i++;
        return substr.substring(i).trim();
    }

}
