package com.tyron.fileeditor.api.impl;

import com.tyron.common.util.ThreadUtil;
import com.tyron.editor.Content;
import com.tyron.editor.event.ContentEvent;
import com.tyron.editor.event.ContentListener;
import com.tyron.editor.event.PrioritizedContentListener;

import org.apache.commons.io.IOUtils;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Predicate;

public class FileDocumentManagerImpl extends FileDocumentManagerBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileDocumentManagerImpl.class);

    private final Set<Content> unsavedContents = new HashSet<>();

    private final PrioritizedContentListener physicalContentTracker = new PrioritizedContentListener() {
        @Override
        public int getPriority() {
            return Integer.MIN_VALUE;
        }

        @Override
        public void contentChanged(@NotNull ContentEvent e) {
            Set<Content> contents = new HashSet<>(unsavedContents);
            contents.add(e.getContent());

            unsavedContents.clear();
            unsavedContents.addAll(contents);
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
        if (file == null || !file.exists()) {
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
        
        Set<Content> set = new HashSet<>(unsavedContents);
        set.remove(content);
        unsavedContents.clear();
        unsavedContents.addAll(set);
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
        saveAllContents(true);
    }

    /**
     * @param isExplicit caused by user directly (Save action) or indirectly (e.g. Compile)
     */
    public void saveAllContents(boolean isExplicit) {
        saveDocuments(null, isExplicit);
    }

    private void saveDocuments(@Nullable Predicate<? super Content> filter, boolean isExplicit) {
        Map<Content, IOException> failedToSave = new HashMap<>();
        Set<Content> vetoed = new HashSet<>();
        while (true) {
            int count = 0;

            for (Content document : unsavedContents) {
                if (filter != null && !filter.test(document)) continue;
                if (failedToSave.containsKey(document)) continue;
                if (vetoed.contains(document)) continue;
                try {
                    doSaveContent(document, isExplicit);
                }
                catch (IOException e) {
                    failedToSave.put(document, e);
                }
//                catch (SaveVetoException e) {
//                    vetoed.add(document);
//                }
                count++;
            }

            if (count == 0) break;
        }

        if (!failedToSave.isEmpty()) {
            handleErrorsOnSave(failedToSave);
        }
    }

    private static class SaveVetoException extends Exception {}

    private void handleErrorsOnSave(@NotNull Map<Content, IOException> failures) {

    }


    @Override
    public void saveContent(@NotNull Content content) {
        saveDocument(content, true);
    }

    @Override
    public boolean isContentUnsaved(@NotNull Content content) {
        return unsavedContents.contains(content);
    }
}
