package org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.encoding.EncodingRegistry;

import java.io.IOException;
import java.util.Collection;

public abstract class NewVirtualFile extends VirtualFile implements VirtualFileWithId {

    @Override
    public boolean isValid() {
        ApplicationManager.getApplication().assertReadAccessAllowed();
        return exists();
    }

    @Override
    public byte[] contentsToByteArray() throws IOException {
        throw new IOException("Cannot get content of " + this);
    }

    @Override
    public abstract @NonNull NewVirtualFileSystem getFileSystem();

    @Override
    public abstract NewVirtualFile getParent();

    @Override
    public abstract @Nullable NewVirtualFile getCanonicalFile();

    @Override
    public abstract @Nullable NewVirtualFile findChild(@NonNull String name);

    public abstract @Nullable NewVirtualFile refreshAndFindChild(@NonNull String name);

    public abstract @Nullable NewVirtualFile findChildIfCached(@NonNull String name);


    public abstract void setTimeStamp(long time) throws IOException;

    @Override
    public abstract @NonNull CharSequence getNameSequence();

    @Override
    public abstract int getId();

    @Override
    public void refresh(boolean asynchronous, boolean recursive, Runnable postRunnable) {
        RefreshQueue.getInstance().refresh(asynchronous, recursive, postRunnable, this);
    }

    @Override
    public abstract void setWritable(boolean writable) throws IOException;

    public abstract void markDirty();

    public abstract void markDirtyRecursively();

    public abstract boolean isDirty();


    public abstract boolean isOffline();


    public abstract void setOffline(boolean offline);

    public abstract void markClean();

    @Override
    public void move(Object requestor, @NonNull VirtualFile newParent) throws IOException {
        if (!exists()) {
            throw new IOException("File to move does not exist: " + getPath());
        }

        if (!newParent.exists()) {
            throw new IOException("Destination folder does not exist: " + newParent.getPath());
        }

        if (!newParent.isDirectory()) {
            throw new IOException("Destination is not a folder: " + newParent.getPath());
        }

        VirtualFile child = newParent.findChild(getName());
        if (child != null) {
            throw new IOException("Destination already exists: " +
                                  newParent.getPath() +
                                  "/" +
                                  getName());
        }

        EncodingRegistry.doActionAndRestoreEncoding(this, () -> {
            getFileSystem().moveFile(requestor, this, newParent);
            return this;
        });
    }

    public abstract @NonNull Collection<VirtualFile> getCachedChildren();

    @SuppressWarnings("SpellCheckingInspection")
    public abstract @NonNull Iterable<VirtualFile> iterInDbChildren();

    @SuppressWarnings("SpellCheckingInspection")
    public @NonNull Iterable<VirtualFile> iterInDbChildrenWithoutLoadingVfsFromOtherProjects() {
        return iterInDbChildren();
    }
}