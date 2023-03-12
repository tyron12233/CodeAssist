package com.tyron.code.ui.editor;

import com.tyron.editor.Editor;
import com.tyron.legacyEditor.Caret;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase;

import io.github.rosemoe.sora.widget.CodeEditor;

public class EditorImpl extends UserDataHolderBase implements Editor {
    private final Caret caret;
    private final Document document;

    public EditorImpl(Caret caret, Document document) {
        this.caret = caret;
        this.document = document;
    }

    @Override
    public Caret getCaret() {
        return caret;
    }

    @Override
    public Document getDocument() {
        return document;
    }
}
