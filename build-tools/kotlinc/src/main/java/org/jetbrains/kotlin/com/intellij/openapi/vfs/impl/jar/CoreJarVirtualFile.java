//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Couple;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.BufferExposingByteArrayInputStream;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;

class CoreJarVirtualFile extends VirtualFile implements VirtualFileWithId {
    private final CoreJarHandler myHandler;
    private final CharSequence myName;
    private final long myLength;
    private final long myTimestamp;
    private final VirtualFile myParent;
    private VirtualFile[] myChildren;
    private final int myId;

    CoreJarVirtualFile(@NotNull CoreJarHandler handler, @NotNull CharSequence name, long length, long timestamp, @Nullable CoreJarVirtualFile parent) {
        this.myChildren = VirtualFile.EMPTY_ARRAY;
        this.myHandler = handler;
        this.myName = name;
        this.myLength = length;
        this.myTimestamp = timestamp;
        this.myParent = parent;

        myId = FileIdStorage.getAndStoreId(this);
    }

    void setChildren(VirtualFile[] children) {
        this.myChildren = children;
    }

    public @NotNull String getName() {
        return this.myName.toString();
    }

    public @NotNull CharSequence getNameSequence() {
        return this.myName;
    }

    public @NotNull VirtualFileSystem getFileSystem() {
        return this.myHandler.getFileSystem();
    }

    public @NotNull String getPath() {
        String path;
        if (this.myParent == null) {
            path = FileUtil.toSystemIndependentName(this.myHandler.getFile().getPath()) + "!/";
        } else {
            String parentPath = this.myParent.getPath();
            StringBuilder answer = new StringBuilder(parentPath.length() + 1 + this.myName.length());
            answer.append(parentPath);
            if (answer.charAt(answer.length() - 1) != '/') {
                answer.append('/');
            }

            answer.append(this.myName);
            path = answer.toString();

        }
        return path;
    }

    public boolean isWritable() {
        return false;
    }

    public boolean isDirectory() {
        return this.myLength < 0L;
    }

    public boolean isValid() {
        return true;
    }

    public VirtualFile getParent() {
        return this.myParent;
    }

    public VirtualFile[] getChildren() {
        return this.myChildren;
    }

    public @NotNull OutputStream getOutputStream(Object requestor, long newModificationStamp, long newTimeStamp) throws IOException {
        throw new UnsupportedOperationException("JarFileSystem is read-only");
    }

    public byte @NotNull [] contentsToByteArray() throws IOException {
        Couple<String> pair = CoreJarFileSystem.splitPath(this.getPath());
        return this.myHandler.contentsToByteArray((String)pair.second);
    }

    public long getTimeStamp() {
        return this.myTimestamp;
    }

    public long getLength() {
        return this.myLength;
    }

    public void refresh(boolean asynchronous, boolean recursive, Runnable postRunnable) {
    }

    public InputStream getInputStream() throws IOException {
        return new BufferExposingByteArrayInputStream(this.contentsToByteArray());
    }

    public long getModificationStamp() {
        return 0L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CoreJarVirtualFile)) {
            return false;
        }
        CoreJarVirtualFile that = (CoreJarVirtualFile) o;
        return this.getPath().equals(that.getPath());
    }

    @Override
    public int hashCode() {
        return getPath().hashCode();
    }

    @Override
    public int getId() {
        return myId;
    }
}
