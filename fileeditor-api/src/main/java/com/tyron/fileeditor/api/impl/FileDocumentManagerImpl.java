package com.tyron.fileeditor.api.impl;

import com.google.common.collect.Sets;
import com.tyron.common.util.ThreadUtil;
import com.tyron.editor.Content;
import com.tyron.editor.event.ContentEvent;
import com.tyron.editor.event.ContentListener;
import com.tyron.editor.event.PrioritizedContentListener;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.provider.DefaultFileContent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.Set;

public class FileDocumentManagerImpl extends FileDocumentManagerBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileDocumentManagerImpl.class);

    private final Set<Content> unsavedContents = Sets.newConcurrentHashSet();

    private final PrioritizedContentListener physicalContentTracker = new PrioritizedContentListener() {
        @Override
        public int getPriority() {
            return Integer.MIN_VALUE;
        }

        @Override
        public void contentChanged(@NotNull ContentEvent e) {
            unsavedContents.add(e.getContent());
        }
    };

    private void saveAllContentsLater() {
        ThreadUtil.runOnUiThread(() -> {
            for (Content document : unsavedContents) {
                saveDocument(document);
            }
        });
    }

    public void saveDocument(@NotNull Content content) {
        saveDocument(content, true);
    }

    public void saveDocument(@NotNull Content content, boolean explicit) {
        if (!unsavedContents.contains(content)) {
            return;
        }

        try {
            doSaveContent(content, explicit);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void doSaveContent(Content content, boolean explicit) throws IOException {
        FileObject file = getFile(content);

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("saving: " + file);
        }
        if (file == null) {
            return;
        }

        LOGGER.trace(" writing...");
        file.delete();
        try (FileContent fileContent = file.getContent()) {
            try (OutputStream outputStream = fileContent.getOutputStream()) {
                IOUtils.write(content, outputStream, StandardCharsets.UTF_8);
            }
        }
        LOGGER.trace(" done");

        unsavedContents.remove(content);
    }

    @Override
    protected Content createContent(@NotNull CharSequence text, @NotNull FileObject file) {
        ServiceLoader<FileDocumentContentProvider> service =
                ServiceLoader.load(FileDocumentContentProvider.class);
        Iterator<FileDocumentContentProvider> iterator = service.iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException("No content providers registered, there should be at least one.");
        }
        FileDocumentContentProvider provider = iterator.next();
        return provider.createContent(text, file);
    }

    @Override
    protected void fileContentLoaded(@NotNull FileObject file, @NotNull Content content) {
        // TODO: implement this
    }

    @Override
    protected @NotNull ContentListener getContentListener() {
        return physicalContentTracker;
    }

    @Override
    public void saveAllContents() {

    }

    @Override
    public void saveContent(@NotNull Content content) {
        saveDocument(content, true);
    }
}
