package org.jetbrains.kotlin.com.intellij.openapi.vfs.impl;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.AppUIExecutor;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState;
import org.jetbrains.kotlin.com.intellij.openapi.application.WriteAction;
import org.jetbrains.kotlin.com.intellij.openapi.application.impl.ApplicationInfoImpl;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.impl.ExtensionPointImpl;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.impl.ExtensionsAreaImpl;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.KeyedExtensionCollector;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.AsyncFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileCopyEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManagerListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileMoveEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePropertyEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.ex.VirtualFileManagerEx;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.BulkFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.CachingVirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import org.jetbrains.kotlin.com.intellij.util.EventDispatcher;
import org.jetbrains.kotlin.com.intellij.util.KeyedLazyInstance;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class VirtualFileManagerImpl extends VirtualFileManagerEx implements Disposable {

    protected static final Logger LOG = Logger.getInstance(VirtualFileManagerImpl.class);
    private static final ExtensionPointImpl<VirtualFileManagerListener>
            MANAGER_LISTENER_EP = ((ExtensionsAreaImpl) ApplicationManager.getApplication().getExtensionArea()).getExtensionPoint("org.jetbrains.kotlin.com.intellij.virtualFileManagerListener");
    private final List<? extends VirtualFileSystem> myPreCreatedFileSystems;
    private final KeyedExtensionCollector<VirtualFileSystem, String> myCollector;
    private final EventDispatcher<VirtualFileListener> myVirtualFileListenerMulticaster;
    private final List<VirtualFileManagerListener> myVirtualFileManagerListeners;
    private final List<AsyncFileListener> myAsyncFileListeners;

    public VirtualFileManagerImpl(@NotNull List<? extends VirtualFileSystem> preCreatedFileSystems) {
        this(preCreatedFileSystems, ApplicationManager.getApplication().getMessageBus());
    }

    public VirtualFileManagerImpl(@NotNull List<? extends VirtualFileSystem> preCreatedFileSystems, @NotNull MessageBus bus) {
        super();
        this.myCollector = new KeyedExtensionCollector<>("org.jetbrains.kotlin.com.intellij.virtualFileSystem");
        this.myVirtualFileListenerMulticaster = EventDispatcher.create(VirtualFileListener.class);
        this.myVirtualFileManagerListeners = ContainerUtil.createLockFreeCopyOnWriteList();
        this.myAsyncFileListeners = ContainerUtil.createLockFreeCopyOnWriteList();
        this.myPreCreatedFileSystems = new ArrayList<>(preCreatedFileSystems);

        for (VirtualFileSystem fileSystem : preCreatedFileSystems) {
            this.myCollector.addExplicitExtension(fileSystem.getProtocol(), fileSystem);
            if (!(fileSystem instanceof CachingVirtualFileSystem)) {
                fileSystem.addVirtualFileListener((VirtualFileListener) this.myVirtualFileListenerMulticaster.getMulticaster());
            }
        }

        if (LOG.isDebugEnabled() && !ApplicationInfoImpl.isInStressTest()) {
            this.addVirtualFileListener(new LoggingListener());
        }

        bus.connect().subscribe(VFS_CHANGES, new BulkVirtualFileListenerAdapter((VirtualFileListener)this.myVirtualFileListenerMulticaster.getMulticaster()));
    }

    public @NotNull List<VirtualFileSystem> getPhysicalFileSystems() {
        List<VirtualFileSystem> physicalFileSystems = new ArrayList<>(myPreCreatedFileSystems);

        ExtensionPoint<KeyedLazyInstance<VirtualFileSystem>> point = myCollector.getPoint();
        if (point != null) {
            for (KeyedLazyInstance<VirtualFileSystem> bean : point.getExtensionList()) {
                VirtualFileSystem fileSystem = bean.getInstance();
                physicalFileSystems.add(fileSystem);
            }
        }

        return physicalFileSystems;
    }

    public void dispose() {
    }

    public long getStructureModificationCount() {
        return 0L;
    }


    public int storeName(@NonNull String name) {
        return 0;
    }

    public @Nullable VirtualFileSystem getFileSystem(@Nullable String protocol) {
        if (protocol == null) {
            return null;
        } else {
            List<VirtualFileSystem> systems = this.myCollector.forKey(protocol);
            return this.selectFileSystem(protocol, systems);
        }
    }

    public long syncRefresh() {
        return doRefresh(false, null);
    }

    public long asyncRefresh(@Nullable Runnable postAction) {
        return doRefresh(true, postAction);
    }

    protected long doRefresh(boolean asynchronous, @Nullable Runnable postAction) {
        if (!asynchronous) {
            ApplicationManager.getApplication().assertWriteAccessAllowed();
        }

        for (VirtualFileSystem fileSystem : getPhysicalFileSystems()) {
            if (!(fileSystem instanceof CachingVirtualFileSystem)) {
                fileSystem.refresh(asynchronous);
            }
        }

        return 0;
    }

    public void refreshWithoutFileWatcher(boolean asynchronous) {
        if (!asynchronous) {
            ApplicationManager.getApplication().assertWriteAccessAllowed();
        }

        for (VirtualFileSystem fileSystem : getPhysicalFileSystems()) {
            if (fileSystem instanceof CachingVirtualFileSystem) {
//                ((CachingVirtualFileSystem)fileSystem).refreshWithoutFileWatcher(asynchronous);
            }
            else {
                fileSystem.refresh(asynchronous);
            }
        }
    }

    protected @Nullable VirtualFileSystem selectFileSystem(@NotNull String protocol, @NotNull List<? extends VirtualFileSystem> candidates) {
        int size = candidates.size();
        if (size == 0) {
            return null;
        } else {
            if (size > 1) {
                LOG.error(protocol + ": " + candidates);
            }

            return (VirtualFileSystem)candidates.get(0);
        }
    }

    public void addVirtualFileListener(@NotNull VirtualFileListener listener) {
        this.myVirtualFileListenerMulticaster.addListener(listener);
    }

    public void addVirtualFileListener(@NonNull VirtualFileListener listener,
                                       @NonNull Disposable parentDisposable) {

    }

    public void removeVirtualFileListener(@NonNull VirtualFileListener listener) {

    }

    public void addAsyncFileListener(@NonNull AsyncFileListener listener,
                                     @NonNull Disposable parentDisposable) {
        myAsyncFileListeners.add(listener);
        Disposer.register(parentDisposable, () -> myAsyncFileListeners.remove(listener));
    }

    @ApiStatus.Internal
    public void addAsyncFileListenersTo(@NotNull List<? super AsyncFileListener> listeners) {
        listeners.addAll(myAsyncFileListeners);
    }

    public void addVirtualFileManagerListener(@NonNull VirtualFileManagerListener listener) {

    }

    public void addVirtualFileManagerListener(@NonNull VirtualFileManagerListener listener,
                                              @NonNull Disposable parentDisposable) {

    }

    public void removeVirtualFileManagerListener(@NonNull VirtualFileManagerListener listener) {

    }

    public void notifyPropertyChanged(@NotNull VirtualFile virtualFile, @NotNull String property, Object oldValue, Object newValue) {
        Application app = ApplicationManager.getApplication();
        ((AppUIExecutor)AppUIExecutor.onWriteThread(ModalityState.NON_MODAL).later().expireWith(app)).submit(() -> {
            if (virtualFile.isValid()) {
                WriteAction.run(() -> {
                    List<VFileEvent> events = Collections.singletonList(new VFilePropertyChangeEvent(this, virtualFile, property, oldValue, newValue, false));
                    BulkFileListener listener = (BulkFileListener)app.getMessageBus().syncPublisher(
                            VirtualFileManager.VFS_CHANGES);
                    listener.before(events);
                    listener.after(events);
                });
            }

        });
    }

    public long getModificationCount() {
        return 0L;
    }

    public @NotNull CharSequence getVFileName(int nameId) {
        throw new AbstractMethodError();
    }

    public VirtualFile findFileByUrl(@NotNull String url) {
        int protocolSepIndex = url.indexOf("://");
        VirtualFileSystem fileSystem = protocolSepIndex < 0 ? null : this.getFileSystem(url.substring(0, protocolSepIndex));
        if (fileSystem == null) {
            return null;
        } else {
            String path = url.substring(protocolSepIndex + "://".length());
            return fileSystem.findFileByPath(path);
        }
    }

    @Override
    public VirtualFile findFileById(int id) {
        String path = FileIdStorage.findPathById(id);
        if (path != null) {
            VirtualFile file;
            if (path.contains("!/")) {
                file = StandardFileSystems.jar().findFileByPath(path);
            } else {
                file = StandardFileSystems.local().findFileByPath(path);
            }
            if (file != null) {
                return file;
            }
        }
        return super.findFileById(id);
    }

    public void fireBeforeRefreshStart(boolean asynchronous) {

    }

    public void fireAfterRefreshFinish(boolean asynchronous) {

    }

    private static class LoggingListener implements VirtualFileListener {
        private LoggingListener() {
        }

        public void propertyChanged(@NotNull VirtualFilePropertyEvent event) {
            VirtualFileManagerImpl.LOG.debug("propertyChanged: file = " + event.getFile() + ", propertyName = " + event.getPropertyName() + ", oldValue = " + event.getOldValue() + ", newValue = " + event.getNewValue() + ", requestor = " + event.getRequestor());
        }

        public void contentsChanged(@NotNull VirtualFileEvent event) {
            VirtualFileManagerImpl.LOG.debug("contentsChanged: file = " + event.getFile() + ", requestor = " + event.getRequestor());
        }

        public void fileCreated(@NotNull VirtualFileEvent event) {
            VirtualFileManagerImpl.LOG.debug("fileCreated: file = " + event.getFile() + ", requestor = " + event.getRequestor());
        }

        public void fileDeleted(@NotNull VirtualFileEvent event) {
            VirtualFileManagerImpl.LOG.debug("fileDeleted: file = " + event.getFile() + ", parent = " + event.getParent() + ", requestor = " + event.getRequestor());
        }

        public void fileMoved(@NotNull VirtualFileMoveEvent event) {
            VirtualFileManagerImpl.LOG.debug("fileMoved: file = " + event.getFile() + ", oldParent = " + event.getOldParent() + ", newParent = " + event.getNewParent() + ", requestor = " + event.getRequestor());
        }

        public void fileCopied(@NotNull VirtualFileCopyEvent event) {
            VirtualFileManagerImpl.LOG.debug("fileCopied: file = " + event.getFile() + ", originalFile = " + event.getOriginalFile() + ", requestor = " + event.getRequestor());
        }

        public void beforeContentsChange(@NotNull VirtualFileEvent event) {
            VirtualFileManagerImpl.LOG.debug("beforeContentsChange: file = " + event.getFile() + ", requestor = " + event.getRequestor());
        }

        public void beforePropertyChange(@NotNull VirtualFilePropertyEvent event) {
            VirtualFileManagerImpl.LOG.debug("beforePropertyChange: file = " + event.getFile() + ", propertyName = " + event.getPropertyName() + ", oldValue = " + event.getOldValue() + ", newValue = " + event.getNewValue() + ", requestor = " + event.getRequestor());
        }

        public void beforeFileDeletion(@NotNull VirtualFileEvent event) {
            VirtualFileManagerImpl.LOG.debug("beforeFileDeletion: file = " + event.getFile() + ", requestor = " + event.getRequestor());
            VirtualFileManagerImpl.LOG.assertTrue(event.getFile().isValid());
        }

        public void beforeFileMovement(@NotNull VirtualFileMoveEvent event) {
            VirtualFileManagerImpl.LOG.debug("beforeFileMovement: file = " + event.getFile() + ", oldParent = " + event.getOldParent() + ", newParent = " + event.getNewParent() + ", requestor = " + event.getRequestor());
        }
    }
}
