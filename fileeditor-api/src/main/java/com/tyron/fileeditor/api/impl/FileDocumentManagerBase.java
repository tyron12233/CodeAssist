package com.tyron.fileeditor.api.impl;

import androidx.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.tyron.editor.Content;
import com.tyron.editor.event.ContentListener;
import com.tyron.fileeditor.api.FileDocumentManager;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.util.FileObjectUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public abstract class FileDocumentManagerBase extends FileDocumentManager {

    private static final String FILE_OBJECT_KEY = "fileObject";

    private final Object lock = new Object();

    public FileDocumentManagerBase() {

    }

    @Nullable
    @Override
    public Content getContent(@NotNull FileObject file) throws FileSystemException {
        Content content = getCachedDocument(file);
        if (content == null) {
            if (!file.exists() || file.isFolder()) {
                return null;
            }
            CharSequence text = loadText(file);
            synchronized (lock) {
                content = getCachedDocument(file);
                if (content != null) {
                    return content;
                }

                content = createContent(text, file);
                content.setModificationStamp(file.getContent().getLastModifiedTime());
                content.addContentListener(getContentListener());
                content.setData(FILE_OBJECT_KEY, file);
                cacheContent(file, content);
            }

            fileContentLoaded(file, content);
        }
        return content;
    }

    @NotNull
    private CharSequence loadText(@NotNull FileObject file) {
        try {
            return FileObjectUtils.getContentAsString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected abstract Content createContent(@NotNull CharSequence text, @NotNull FileObject file);

    private final Cache<FileObject, Content> documentCache = CacheBuilder.newBuilder().weakValues().build();

    @Nullable
    @Override
    public Content getCachedDocument(@NotNull FileObject file) {
        return documentCache.getIfPresent(file);
    }

    @Nullable
    @Override
    public FileObject getFile(@NotNull Content content) {
        Object data = content.getData(FILE_OBJECT_KEY);
        if (data instanceof FileObject) {
            return ((FileObject) data);
        }
        return null;
    }

    private void cacheContent(@NotNull FileObject file, @NotNull Content content) {
        documentCache.put(file, content);
    }

    protected abstract void fileContentLoaded(@NotNull FileObject file, @NotNull Content content);

    protected abstract @NotNull ContentListener getContentListener();
}
