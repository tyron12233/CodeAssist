package com.tyron.completion;

import androidx.annotation.Nullable;

import com.tyron.completion.lookup.Lookup;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.editor.Editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

import java.util.Objects;

import io.github.rosemoe.sora.widget.CodeEditor;

public class InsertionContext {
    public static final OffsetKey TAIL_OFFSET = OffsetKey.create("tailOffset", true);

    private final OffsetMap myOffsetMap;
    private final char myCompletionChar;
    private final LookupElement[] myElements;
    private final PsiFile myFile;
    private final Editor myEditor;
    private Runnable myLaterRunnable;
    private boolean myAddCompletionChar;

    public InsertionContext(final OffsetMap offsetMap, final char completionChar, final LookupElement[] elements,
                            @NotNull final PsiFile file,
                            @NotNull final Editor editor, final boolean addCompletionChar) {
        myOffsetMap = offsetMap;
        myCompletionChar = completionChar;
        myElements = elements;
        myFile = file;
        myEditor = editor;
        setTailOffset(editor.getCaret().getStart());
        myAddCompletionChar = addCompletionChar;
    }

    public void setTailOffset(final int offset) {
        myOffsetMap.addOffset(TAIL_OFFSET, offset);
    }

    public int getTailOffset() {
        return myOffsetMap.getOffset(TAIL_OFFSET);
    }

    @NotNull
    public PsiFile getFile() {
        return myFile;
    }

    @NotNull
    public Editor getEditor() {
        return myEditor;
    }

    public void commitDocument() {
        PsiDocumentManager.getInstance(getProject()).commitDocument(getDocument());
    }

    @NotNull
    public Document getDocument() {
        return myEditor.getDocument();
    }

    public int getOffset(OffsetKey key) {
        return getOffsetMap().getOffset(key);
    }

    public OffsetMap getOffsetMap() {
        return myOffsetMap;
    }

    public OffsetKey trackOffset(int offset, boolean movableToRight) {
        final OffsetKey key = OffsetKey.create("tracked", movableToRight);
        getOffsetMap().addOffset(key, offset);
        return key;
    }

    public int getStartOffset() {
        return myOffsetMap.getOffset(CompletionInitializationContext.START_OFFSET);
    }

    public char getCompletionChar() {
        return myCompletionChar;
    }

    public LookupElement[] getElements() {
        return myElements;
    }

    @NotNull
    public Project getProject() {
        return myFile.getProject();
    }

    public int getSelectionEndOffset() {
        return myOffsetMap.getOffset(CompletionInitializationContext.SELECTION_END_OFFSET);
    }

    @Nullable
    public Runnable getLaterRunnable() {
        return myLaterRunnable;
    }

    public void setLaterRunnable(@Nullable final Runnable laterRunnable) {
        myLaterRunnable = laterRunnable;
    }

    /**
     * @param addCompletionChar Whether completionChar should be added to document at tail offset (see {@link #TAIL_OFFSET}) after insert handler (default: {@code true}).
     */
    public void setAddCompletionChar(final boolean addCompletionChar) {
        myAddCompletionChar = addCompletionChar;
    }

    public boolean shouldAddCompletionChar() {
        return myAddCompletionChar;
    }


    public static boolean shouldAddCompletionChar(char completionChar) {
        return completionChar != Lookup.AUTO_INSERT_SELECT_CHAR &&
               completionChar != Lookup.REPLACE_SELECT_CHAR &&
               completionChar != Lookup.NORMAL_SELECT_CHAR;
    }

    public InsertionContext forkByOffsetMap() {
        return new InsertionContext(myOffsetMap.copyOffsets(myEditor.getDocument()), myCompletionChar, myElements, myFile, myEditor, myAddCompletionChar);
    }
}