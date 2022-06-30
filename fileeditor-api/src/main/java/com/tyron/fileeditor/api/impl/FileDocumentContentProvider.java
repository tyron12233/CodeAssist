package com.tyron.fileeditor.api.impl;

import com.tyron.editor.Content;

import org.apache.commons.vfs2.FileObject;
import org.jetbrains.annotations.NotNull;

public interface FileDocumentContentProvider {
    Content createContent(@NotNull CharSequence text, @NotNull FileObject file);
}
