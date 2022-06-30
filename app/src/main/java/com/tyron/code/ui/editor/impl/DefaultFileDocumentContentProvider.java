package com.tyron.code.ui.editor.impl;

import com.tyron.code.ui.editor.impl.text.rosemoe.ContentWrapper;
import com.tyron.editor.Content;
import com.tyron.fileeditor.api.impl.FileDocumentContentProvider;

import org.apache.commons.vfs2.FileObject;

public class DefaultFileDocumentContentProvider implements FileDocumentContentProvider {
    @Override
    public Content createContent(CharSequence text, FileObject file) {
        return new ContentWrapper(text);
    }
}
