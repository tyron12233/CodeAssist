package jdk.internal.jrtfs;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Set;

public class JrtFileSystemWrapper extends FileSystem {

    private final JrtFileSystem mFileSystem;

    public JrtFileSystemWrapper(JrtFileSystem mFileSystem) {
        this.mFileSystem = mFileSystem;
    }

    @Override
    public FileSystemProvider provider() {
        return mFileSystem.provider();
    }

    @Override
    public void close() throws IOException {
        mFileSystem.close();
    }

    @Override
    public boolean isOpen() {
        return mFileSystem.isOpen();
    }

    @Override
    public boolean isReadOnly() {
        return mFileSystem.isReadOnly();
    }

    @Override
    public String getSeparator() {
        return mFileSystem.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return mFileSystem.getRootDirectories();
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return mFileSystem.getFileStores();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return mFileSystem.supportedFileAttributeViews();
    }

    @Override
    public Path getPath(String first, String... more) {
        return mFileSystem.getPath(first, more);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        return mFileSystem.getPathMatcher(syntaxAndPattern);
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return mFileSystem.getUserPrincipalLookupService();
    }

    @Override
    public WatchService newWatchService() throws IOException {
        return mFileSystem.newWatchService();
    }
}