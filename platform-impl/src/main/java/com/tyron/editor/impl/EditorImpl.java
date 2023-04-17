package com.tyron.editor.impl;

import com.tyron.editor.EditorKind;
import com.tyron.editor.ex.EditorEx;
import com.tyron.legacyEditor.Caret;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.DocumentEx;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public final class EditorImpl extends UserDataHolderBase implements EditorEx {

    public static final int TEXT_ALIGNMENT_LEFT = 0;
    public static final int TEXT_ALIGNMENT_RIGHT = 1;

    private static final float MIN_FONT_SIZE = 8;
    private static final Logger LOG = Logger.getInstance(EditorImpl.class);
    static final Logger EVENT_LOG = Logger.getInstance("editor.input.events");

    private final @Nullable Project myProject;
    private final @NotNull DocumentEx myDocument;
    private VirtualFile myVirtualFile;
    private final EditorKind myKind;
    private boolean myIsViewer;

    EditorImpl(@NotNull Document document, boolean viewer, @Nullable Project project, @NotNull EditorKind kind, @Nullable VirtualFile file) {
        assertIsDispatchThread();

        myProject = project;
        myDocument = (DocumentEx)document;
        myVirtualFile = file;
        myKind = kind;
        myIsViewer = viewer;
    }

    @Override
    public Project getProject() {
        return myProject;
    }

    @Override
    public Caret getCaret() {
        return null;
    }

    @Override
    public @NotNull DocumentEx getDocument() {
        return myDocument;
    }

    @Override
    public void setViewer(boolean isViewer) {
        myIsViewer = isViewer;
    }

    @Override
    public void setInsertMode(boolean val) {

    }

    @Override
    public void setColumnMode(boolean val) {

    }

    @Override
    public void reinitSettings() {

    }

    @Override
    public void setFile(VirtualFile vFile) {
        myVirtualFile = vFile;
    }

    @Override
    public void setFontSize(int fontSize) {

    }

    @Override
    public void setFontSize(float fontSize) {
        EditorEx.super.setFontSize(fontSize);
    }

    private void assertIsDispatchThread() {
        assert ApplicationManager.getApplication().isDispatchThread();
    }

    public void throwDisposalError(String s) {

    }

    public void release() {

    }
}
