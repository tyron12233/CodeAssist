package org.jetbrains.kotlin.com.intellij.openapi.vfs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalVirtualFile;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class CoreLocalVirtualFileEx extends CoreLocalVirtualFile implements VirtualFileWithId {
    private final CoreLocalFileSystem fileSystem;
    private final File ioFile;

    public CoreLocalVirtualFileEx(CoreLocalFileSystem coreLocalFileSystem, File ioFile) {
        super(coreLocalFileSystem, ioFile);
        fileSystem = coreLocalFileSystem;
        this.ioFile = ioFile;
    }

    @Override
    public VirtualFile getParent() {
        File parentFile = this.ioFile.getParentFile();
        return parentFile != null ? new CoreLocalVirtualFileEx(this.fileSystem, parentFile) : null;
    }

    @Override
    public boolean isDirectory() {
        return ioFile.isDirectory();
    }

    @Override
    public boolean isWritable() {
        return ioFile.canWrite();
    }

    @Override
    public @NonNull OutputStream getOutputStream(Object requestor,
                                                 long newModificationStamp,
                                                 long newTimeStamp) throws IOException {
        return Files.newOutputStream(ioFile.toPath());
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof CoreLocalVirtualFileEx) {
            CoreLocalVirtualFileEx that = (CoreLocalVirtualFileEx) o;
            return this.ioFile.equals(that.ioFile);
        }
        return super.equals(o);
    }

    @NonNull
    @Override
    public Path toNioPath() {
        return ioFile.toPath();
    }

    @Override
    public int getId() {
        return FileIdStorage.getAndStoreId(this);
    }
}
