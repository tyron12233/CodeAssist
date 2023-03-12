package com.tyron.completion;

import static org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns.not;
import static org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns.string;

import com.tyron.completion.impl.CompletionAssertions;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.impl.LookupItem;
import com.tyron.editor.Editor;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.diagnostic.PluginException;
import org.jetbrains.kotlin.com.intellij.diagnostic.ThreadDumper;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Attachment;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.project.IndexNotReadyException;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.ObjectPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiComment;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.ReferenceRange;
import org.jetbrains.kotlin.com.intellij.psi.meta.PsiMetaData;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.UnmodifiableIterator;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;

import io.github.rosemoe.sora.widget.CodeEditor;

public class CompletionUtil {

    public static final ObjectPattern.Capture<Character> NOT_JAVA_ID =
            not(new ElementPattern<Character>() {
                @Override
                public boolean accepts(@Nullable Object o) {
                    if (o instanceof Character) {
                        return Character.isJavaIdentifierPart((Character) o);
                    }
                    return false;
                }

                @Override
                public boolean accepts(@Nullable Object o, ProcessingContext processingContext) {
                    if (o instanceof Character) {
                        return Character.isJavaIdentifierPart((Character) o);
                    }
                    return false;
                }

                @Override
                public ElementPatternCondition<Character> getCondition() {
                    return null;
                }
            });
    public static final @NonNls String DUMMY_IDENTIFIER =
            CompletionInitializationContext.DUMMY_IDENTIFIER;
    public static final @NonNls String DUMMY_IDENTIFIER_TRIMMED = DUMMY_IDENTIFIER.trim();

    public static Iterable<String> iterateLookupStrings(@NotNull final LookupElement element) {
        return new Iterable<String>() {
            @NotNull
            @Override
            public Iterator<String> iterator() {
                final Iterator<String> original = element.getAllLookupStrings().iterator();
                return new UnmodifiableIterator<String>(original) {
                    @Override
                    public boolean hasNext() {
                        try {
                            return super.hasNext();
                        } catch (ConcurrentModificationException e) {
                            throw handleCME(e);
                        }
                    }

                    @Override
                    public String next() {
                        try {
                            return super.next();
                        } catch (ConcurrentModificationException e) {
                            throw handleCME(e);
                        }
                    }

                    private RuntimeException handleCME(ConcurrentModificationException cme) {
                        RuntimeExceptionWithAttachments ewa = new RuntimeExceptionWithAttachments(
                                "Error while traversing lookup strings of " +
                                element +
                                " of " +
                                element.getClass(),
                                (String) null,
                                new Attachment("threadDump.txt",
                                        ThreadDumper.dumpThreadsToString()));
                        ewa.initCause(cme);
                        return ewa;
                    }
                };
            }
        };
    }

    public static InsertionContext createInsertionContext(@Nullable List<LookupElement> lookupItems,
                                                          LookupElement item,
                                                          char completionChar,
                                                          Editor editor,
                                                          PsiFile psiFile,
                                                          int caretOffset,
                                                          int idEndOffset,
                                                          OffsetMap offsetMap) {
        int initialStartOffset = Math.max(0, caretOffset - item.getLookupString().length());

        return createInsertionContext(lookupItems,
                completionChar,
                editor,
                psiFile,
                initialStartOffset,
                caretOffset,
                idEndOffset,
                offsetMap);
    }

    public static InsertionContext createInsertionContext(@Nullable List<LookupElement> lookupItems,
                                                          char completionChar,
                                                          Editor editor,
                                                          PsiFile psiFile,
                                                          int startOffset,
                                                          int caretOffset,
                                                          int idEndOffset,
                                                          OffsetMap offsetMap) {

        offsetMap.addOffset(CompletionInitializationContext.START_OFFSET, startOffset);
        offsetMap.addOffset(CompletionInitializationContext.SELECTION_END_OFFSET, caretOffset);
        offsetMap.addOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET, idEndOffset);

        List<LookupElement> items = lookupItems == null ? Collections.emptyList() : lookupItems;

        return new InsertionContext(offsetMap,
                completionChar,
                items.toArray(LookupElement.EMPTY_ARRAY),
                psiFile,
                editor,
                InsertionContext.shouldAddCompletionChar(completionChar));
    }

    /**
     * @return a prefix from completion matching calculated by a reference found at parameters'
     * offset
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


    private static String findPrefixStatic(final PsiElement insertedElement,
                                           final int offsetInFile,
                                           ElementPattern<Character> prefixStartTrim) {
        if (insertedElement == null) {
            return "";
        }

        final Document document =
                insertedElement.getContainingFile().getViewProvider().getDocument();
        assert document != null;
//        LOG.assertTrue(!PsiDocumentManager.getInstance(insertedElement.getProject())
//        .isUncommited(document), "Uncommitted");

        final String prefix = CompletionUtil.findReferencePrefix(insertedElement, offsetInFile);
        if (prefix != null) {
            return prefix;
        }

        if (insertedElement.getTextRange()
                    .equals(insertedElement.getContainingFile().getTextRange()) ||
            insertedElement instanceof PsiComment) {
            return findJavaIdentifierPrefix(insertedElement, offsetInFile);
        }

        return findPrefixDefault(insertedElement, offsetInFile, prefixStartTrim);
    }


    public static String findPrefixStatic(final PsiElement insertedElement,
                                          final int offsetInFile) {
        return findPrefixStatic(insertedElement, offsetInFile, NOT_JAVA_ID);
    }

    private static String findPrefixDefault(final PsiElement insertedElement,
                                            final int offset,
                                            @NotNull final ElementPattern trimStart) {
        String substr = insertedElement.getText()
                .substring(0, offset - insertedElement.getTextRange().getStartOffset());
        if (substr.length() == 0 || Character.isWhitespace(substr.charAt(substr.length() - 1))) {
            return "";
        }

        substr = substr.trim();

        int i = 0;
        while (substr.length() > i && trimStart.accepts(substr.charAt(i))) i++;
        return substr.substring(i).trim();
    }

    @NotNull
    public static LookupElement objectToLookupItem(final @NotNull Object object) {
        if (object instanceof LookupElement) {
            return (LookupElement) object;
        }

        String s = null;
        TailType tailType = TailType.NONE;
        if (object instanceof PsiElement) {
            s = PsiUtilCore.getName((PsiElement) object);
        } else if (object instanceof PsiMetaData) {
            s = ((PsiMetaData) object).getName();
        } else if (object instanceof String) {
            s = (String) object;
        }
//        else if (object instanceof PresentableLookupValue) {
//            s = ((PresentableLookupValue)object).getPresentation();
//        }
        if (s == null) {
            throw PluginException.createByClass("Null string for object: " +
                                                object +
                                                " of " +
                                                object.getClass(), null, object.getClass());
        }

        LookupItem<?> item = new LookupItem<>(object, s);

        item.setAttribute(LookupItem.TAIL_TYPE_ATTR, tailType);
        return item;
    }

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
        return findIdentifierPrefix(position, offsetInFile, new ElementPattern<Character>() {
            @Override
            public boolean accepts(@Nullable Object o) {
                return Character.isJavaIdentifierPart((Character) o);
            }

            @Override
            public boolean accepts(@Nullable Object o, ProcessingContext processingContext) {
                return Character.isJavaIdentifierPart((Character) o);
            }

            @Override
            public ElementPatternCondition<Character> getCondition() {
                return null;
            }
        }, new ElementPattern<Character>() {
            @Override
            public boolean accepts(@Nullable Object o) {
                return Character.isJavaIdentifierStart((Character) o);
            }

            @Override
            public boolean accepts(@Nullable Object o, ProcessingContext processingContext) {
                return Character.isJavaIdentifierStart((Character) o);
            }

            @Override
            public ElementPatternCondition<Character> getCondition() {
                return null;
            }
        });
    }

    /**
     * @return a prefix for completion matching, calculated from the given element's text and the
     * offsets.
     * The prefix is the longest substring from inside {@code position}'s text,
     * ending at {@code offsetInFile}, beginning with a character
     * satisfying {@code idStart}, and with all other characters satisfying {@code idPart}.
     */
    @NotNull
    public static String findIdentifierPrefix(@Nullable PsiElement position,
                                              int offsetInFile,
                                              @NotNull ElementPattern<Character> idPart,
                                              @NotNull ElementPattern<Character> idStart) {
        if (position == null) {
            return "";
        }
        int startOffset = position.getTextRange().getStartOffset();
        return findInText(offsetInFile,
                startOffset,
                idPart,
                idStart,
                position.getNode().getChars());
    }

    public static String findIdentifierPrefix(@NotNull Document document,
                                              int offset,
                                              ElementPattern<Character> idPart,
                                              ElementPattern<Character> idStart) {
        final CharSequence text = document.getImmutableCharSequence();
        return findInText(offset, 0, idPart, idStart, text);
    }

    @NotNull
    private static String findInText(int offset,
                                     int startOffset,
                                     ElementPattern<Character> idPart,
                                     ElementPattern<Character> idStart,
                                     CharSequence text) {
        final int offsetInElement = offset - startOffset;
        int start = offsetInElement - 1;
        while (start >= 0) {
            if (!idPart.accepts(text.charAt(start))) {
                break;
            }
            --start;
        }
        while (start + 1 < offsetInElement && !idStart.accepts(text.charAt(start + 1))) {
            start++;
        }

        return text.subSequence(start + 1, offsetInElement).toString().trim();
    }

    public static int calcIdEndOffset(OffsetMap offsetMap, Editor editor, Integer initOffset) {
        return offsetMap.containsOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET) ?
                offsetMap.getOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET) :
                CompletionInitializationContext.calcDefaultIdentifierEnd(editor, initOffset);
    }
}
