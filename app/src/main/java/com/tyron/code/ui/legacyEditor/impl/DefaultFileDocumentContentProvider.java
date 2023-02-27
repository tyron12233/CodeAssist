package com.tyron.code.ui.legacyEditor.impl;

import com.tyron.editor.Content;
import com.tyron.fileeditor.api.impl.FileDocumentContentProvider;

import org.apache.commons.vfs2.FileObject;

public class DefaultFileDocumentContentProvider implements FileDocumentContentProvider {
    @Override
    public Content createContent(CharSequence text, FileObject file) {
        throw new UnsupportedOperationException();
    }
}
