package com.tyron.completion;

import com.tyron.editor.snippet.Snippet;

import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

import io.github.rosemoe.sora.widget.CodeEditor;

public class InsertionContext {

    public static final OffsetKey TAIL_OFFSET = OffsetKey.create("tailOffset", true);

    private final PsiFile file;
    private final CodeEditor editor;

    private int tailOffset;
    private boolean addCompletionChar;

    public InsertionContext(CodeEditor editor, PsiFile file) {
        this.editor = editor;
        this.file = file;
    }

    public boolean shouldAddCompletionChar() {
        return addCompletionChar;
    }

    public char getCompletionChar() {
        return '.';
    }

    public CodeEditor getEditor() {
        return editor;
    }

    public int getTailOffset() {
        return tailOffset;
    }

    public void setAddCompletionChar(boolean b) {
        this.addCompletionChar = b;
    }

    public PsiFile getFile() {
        return file;
    }

    public void setTailOffset(int tailOffset) {
        this.tailOffset = tailOffset;
    }

    public int getStartOffset() {
        return 0;
    }

    public Project getProject() {
        return file.getProject();
    }
}