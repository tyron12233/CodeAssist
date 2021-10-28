package com.tyron.psi.completion;

import com.tyron.psi.editor.Caret;
import com.tyron.psi.editor.Editor;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

import java.util.Objects;

/**
 * @author peter
 */
public class CompletionInitializationContext {
    public static final OffsetKey START_OFFSET = OffsetKey.create("startOffset", false);
    public static final OffsetKey SELECTION_END_OFFSET = OffsetKey.create("selectionEnd");
    public static final OffsetKey IDENTIFIER_END_OFFSET = OffsetKey.create("identifierEnd");

    /**
     * A default string that is inserted into the file before completion to guarantee that there'll always be some non-empty element there
     */
    public static @NonNls final String DUMMY_IDENTIFIER = "IntellijIdeaRulezzz ";
    public static @NonNls
    final String DUMMY_IDENTIFIER_TRIMMED = "IntellijIdeaRulezzz";
    private final Editor myEditor;
    @NotNull
    private final Caret myCaret;
    private final PsiFile myFile;
    private final CompletionType myCompletionType;
    private final int myInvocationCount;
    private final OffsetMap myOffsetMap;
    private String myDummyIdentifier = DUMMY_IDENTIFIER;
    private final Language myPositionLanguage;

    public CompletionInitializationContext(final Editor editor,
                                           final @NotNull Caret caret,
                                           Language language,
                                           final PsiFile file,
                                           final CompletionType completionType,
                                           int invocationCount) {
        myEditor = editor;
        myCaret = caret;
        myPositionLanguage = language;
        myFile = file;
        myCompletionType = completionType;
        myInvocationCount = invocationCount;
        myOffsetMap = new OffsetMap(editor.getDocument());

        myOffsetMap.addOffset(START_OFFSET, calcStartOffset(caret));
        myOffsetMap.addOffset(SELECTION_END_OFFSET, calcSelectionEnd(caret));
        myOffsetMap.addOffset(IDENTIFIER_END_OFFSET, calcDefaultIdentifierEnd(editor, calcSelectionEnd(caret)));
    }

    public static int calcSelectionEnd(Caret caret) {
        return caret.hasSelection() ? caret.getSelectionEnd() : caret.getOffset();
    }

    public static int calcStartOffset(Caret caret) {
        return caret.hasSelection() ? caret.getSelectionStart() : caret.getOffset();
    }

    public static int calcDefaultIdentifierEnd(Editor editor, int startFrom) {
        final CharSequence text = editor.getDocument().getCharsSequence();
        int idEnd = startFrom;
        while (idEnd < text.length() && Character.isJavaIdentifierPart(text.charAt(idEnd))) {
            idEnd++;
        }
        return idEnd;
    }

    public void setDummyIdentifier(@NotNull String dummyIdentifier) {
        myDummyIdentifier = dummyIdentifier;
    }

    @NotNull
    public Language getPositionLanguage() {
        return Objects.requireNonNull(myPositionLanguage);
    }

    public String getDummyIdentifier() {
        return myDummyIdentifier;
    }

    @NotNull
    public Editor getEditor() {
        return myEditor;
    }

    @NotNull
    public Caret getCaret() {
        return myCaret;
    }

    @NotNull
    public CompletionType getCompletionType() {
        return myCompletionType;
    }

    @NotNull
    public Project getProject() {
        return myFile.getProject();
    }

    @NotNull
    public PsiFile getFile() {
        return myFile;
    }

    @NotNull
    public OffsetMap getOffsetMap() {
        return myOffsetMap;
    }

    public int getStartOffset() {
        return myOffsetMap.getOffset(START_OFFSET);
    }

    public int getSelectionEndOffset() {
        return myOffsetMap.getOffset(SELECTION_END_OFFSET);
    }

    public int getIdentifierEndOffset() {
        return myOffsetMap.getOffset(IDENTIFIER_END_OFFSET);
    }

    public int getReplacementOffset() {
        return getIdentifierEndOffset();
    }

    public int getInvocationCount() {
        return myInvocationCount;
    }

    /**
     * Mark the offset up to which the text will be deleted if a completion variant is selected using Replace character (Tab)
     */
    public void setReplacementOffset(int idEnd) {
        myOffsetMap.addOffset(IDENTIFIER_END_OFFSET, idEnd);
    }
}