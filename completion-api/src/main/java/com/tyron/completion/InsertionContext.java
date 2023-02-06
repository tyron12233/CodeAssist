package com.tyron.completion;

import com.tyron.editor.snippet.Snippet;

import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

import io.github.rosemoe.sora.widget.CodeEditor;

public class InsertionContext {

    public static final OffsetKey TAIL_OFFSET = OffsetKey.create("tailOffset", true);

    public boolean shouldAddCompletionChar() {
        return false;
    }

    public char getCompletionChar() {
        return 0;
    }

    public CodeEditor getEditor() {
        throw new UnsupportedOperationException();
    }

    public int getTailOffset() {
        return 0;
    }

    public void setAddCompletionChar(boolean b) {

    }

    public PsiFile getFile() {
        return null;
    }

    public void setTailOffset(int i) {

    }

    public int getStartOffset() {
        return 0;
    }

    public Project getProject() {
        return null;
    }
}