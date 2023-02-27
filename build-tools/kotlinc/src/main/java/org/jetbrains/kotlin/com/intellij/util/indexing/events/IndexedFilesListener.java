package org.jetbrains.kotlin.com.intellij.util.indexing.events;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.AsyncFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.ManagingFS;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexImpl;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexingFlag;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.List;

public abstract class IndexedFilesListener implements AsyncFileListener {

    @NotNull
    private final VfsEventsMerger myEventMerger = new VfsEventsMerger();

    @NotNull
    public VfsEventsMerger getEventMerger() {
        return myEventMerger;
    }

    public void scheduleForIndexingRecursively(@NotNull VirtualFile file, boolean onlyContentDependent) {
        IndexingFlag.cleanProcessedFlagRecursively(file);
        if (file.isDirectory()) {
            final ContentIterator iterator = fileOrDir -> {
                myEventMerger.recordFileEvent(fileOrDir, onlyContentDependent);
                return true;
            };

            iterateIndexableFiles(file, iterator);
        }
        else {
            myEventMerger.recordFileEvent(file, onlyContentDependent);
        }
    }

    protected abstract void iterateIndexableFiles(@NotNull VirtualFile file, @NotNull ContentIterator iterator);

    private static void collectFilesRecursively(@NotNull VirtualFile file, @NotNull Int2ObjectMap<VirtualFile> id2File) {
        VfsUtilCore.visitChildrenRecursively(file, new VirtualFileVisitor<Void>() {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                ProgressManager.checkCanceled();
                if (file instanceof VirtualFileWithId) {
                    id2File.put(((VirtualFileWithId)file).getId(), file);
                }
                return !file.isDirectory() || CoreFileBasedIndex.isMock(file) || ManagingFS.getInstance().wereChildrenAccessed(file);
            }

            @Override
            public Iterable<VirtualFile> getChildrenIterable(@NotNull VirtualFile file) {
                return file instanceof NewVirtualFile ? ((NewVirtualFile)file).iterInDbChildren() : null;
            }
        });
    }

    @Override
    @NotNull
    public ChangeApplier prepareChange(@NotNull List<? extends @NotNull VFileEvent> events) {
        Int2ObjectMap<VirtualFile> deletedFiles = new Int2ObjectOpenHashMap<>();
        for (VFileEvent event : events) {
            if (event instanceof VFileDeleteEvent) {
                collectFilesRecursively(((VFileDeleteEvent)event).getFile(), deletedFiles);
            }
        }

        return new ChangeApplier() {
            @Override
            public void beforeVfsChange() {
                for (VirtualFile file : deletedFiles.values()) {
                    myEventMerger.recordFileRemovedEvent(file);
                }
            }

            @Override
            public void afterVfsChange() {
                processAfterEvents(events);
            }
        };
    }

    private void processAfterEvents(@NotNull List<? extends VFileEvent> events) {
        for (VFileEvent event : events) {
            VirtualFile fileToIndex = null;
            boolean onlyContentDependent = true;

            if (event instanceof VFileContentChangeEvent) {
                fileToIndex = event.getFile();
            }
            else if (event instanceof VFileCopyEvent) {
                VFileCopyEvent ce = (VFileCopyEvent) event;
                final VirtualFile copy = ce.getNewParent().findChild(ce.getNewChildName());
                if (copy != null) {
                    fileToIndex = copy;
                    onlyContentDependent = false;
                }
            }
            else if (event instanceof VFileCreateEvent) {
                final VirtualFile newChild = event.getFile();
                if (newChild != null) {
                    fileToIndex = newChild;
                    onlyContentDependent = false;
                }
            }
            else if (event instanceof VFileMoveEvent) {
                fileToIndex = event.getFile();
                onlyContentDependent = false;
            }
            else if (event instanceof VFilePropertyChangeEvent) {
                VFilePropertyChangeEvent pce = (VFilePropertyChangeEvent) event;
                String propertyName = pce.getPropertyName();
                if (propertyName.equals(VirtualFile.PROP_NAME)) {
                    // indexes may depend on file name
                    fileToIndex = pce.getFile();
                    onlyContentDependent = false;
                }
                else if (propertyName.equals(VirtualFile.PROP_ENCODING)) {
                    fileToIndex = pce.getFile();
                }
            }

            if (fileToIndex != null) {
                scheduleForIndexingRecursively(fileToIndex, onlyContentDependent);
            }
        }
    }
}
