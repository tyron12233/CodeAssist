package com.tyron.builder.api.internal.file.archive;

import org.apache.tools.zip.ZipEntry;
import org.apache.tools.zip.ZipFile;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.file.FileVisitor;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.file.AbstractFileTreeElement;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.internal.file.Chmod;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ZipFileTree extends AbstractArchiveFileTree {
    private final Provider<File> fileProvider;
    private final File tmpDir;
    private final Chmod chmod;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;
    private final FileHasher fileHasher;

    public ZipFileTree(Provider<File> zipFile,
                       File tmpDir,
                       Chmod chmod,
                       DirectoryFileTreeFactory directoryFileTreeFactory,
                       FileHasher fileHasher) {
        this.fileProvider = zipFile;
        this.tmpDir = tmpDir;
        this.chmod = chmod;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
        this.fileHasher = fileHasher;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public String getDisplayName() {
        return String.format("ZIP '%s'", fileProvider.getOrNull());
    }

    @Override
    public DirectoryFileTree getMirror() {
        return directoryFileTreeFactory.create(getExpandedDir());
    }

    @Override
    public void visit(FileVisitor visitor) {
        File zipFile = fileProvider.get();
        if (!zipFile.exists()) {
            throw new InvalidUserDataException(String.format("Cannot expand %s as it does not exist.", getDisplayName()));
        }
        if (!zipFile.isFile()) {
            throw new InvalidUserDataException(String.format("Cannot expand %s as it is not a file.", getDisplayName()));
        }

        AtomicBoolean stopFlag = new AtomicBoolean();

        try {
            ZipFile zip = new ZipFile(zipFile);
            File expandedDir = getExpandedDir();
            try {
                // The iteration order of zip.getEntries() is based on the hash of the zip entry. This isn't much use
                // to us. So, collect the entries in a map and iterate over them in alphabetical order.
                Map<String, ZipEntry> entriesByName = new TreeMap<String, ZipEntry>();
                Enumeration entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = (ZipEntry) entries.nextElement();
                    entriesByName.put(entry.getName(), entry);
                }
                Iterator<ZipEntry> sortedEntries = entriesByName.values().iterator();
                while (!stopFlag.get() && sortedEntries.hasNext()) {
                    ZipEntry entry = sortedEntries.next();
                    if (entry.isDirectory()) {
                        visitor.visitDir(new DetailsImpl(zipFile, expandedDir, entry, zip, stopFlag, chmod));
                    } else {
                        visitor.visitFile(new DetailsImpl(zipFile, expandedDir, entry, zip, stopFlag, chmod));
                    }
                }
            } finally {
                zip.close();
            }
        } catch (Exception e) {
            throw new BuildException(String.format("Could not expand %s.", getDisplayName()), e);
        }
    }

    @Override
    public Provider<File> getBackingFileProvider() {
        return fileProvider;
    }

    private File getExpandedDir() {
        File zipFile = fileProvider.get();
        String expandedDirName = zipFile.getName() + "_" + fileHasher.hash(zipFile);
        return new File(tmpDir, expandedDirName);
    }

    private static class DetailsImpl extends AbstractFileTreeElement implements FileVisitDetails {
        private final File originalFile;
        private final File expandedDir;
        private final ZipEntry entry;
        private final ZipFile zip;
        private final AtomicBoolean stopFlag;
        private File file;

        public DetailsImpl(File originalFile, File expandedDir, ZipEntry entry, ZipFile zip, AtomicBoolean stopFlag, Chmod chmod) {
            super(chmod);
            this.originalFile = originalFile;
            this.expandedDir = expandedDir;
            this.entry = entry;
            this.zip = zip;
            this.stopFlag = stopFlag;
        }

        @Override
        public String getDisplayName() {
            return String.format("zip entry %s!%s", originalFile, entry.getName());
        }

        @Override
        public void stopVisiting() {
            stopFlag.set(true);
        }

        @Override
        public File getFile() {
            if (file == null) {
                file = new File(expandedDir, entry.getName());
                if (!file.exists()) {
                    copyTo(file);
                }
            }
            return file;
        }

        @Override
        public long getLastModified() {
            return entry.getTime();
        }

        @Override
        public boolean isDirectory() {
            return entry.isDirectory();
        }

        @Override
        public long getSize() {
            return entry.getSize();
        }

        @Override
        public InputStream open() {
            try {
                return zip.getInputStream(entry);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public RelativePath getRelativePath() {
            return new RelativePath(!entry.isDirectory(), entry.getName().split("/"));
        }

        @Override
        public int getMode() {
            int unixMode = entry.getUnixMode() & 0777;
            if (unixMode == 0) {
                //no mode infos available - fall back to defaults
                if (isDirectory()) {
                    unixMode = FileSystem.DEFAULT_DIR_MODE;
                } else {
                    unixMode = FileSystem.DEFAULT_FILE_MODE;
                }
            }
            return unixMode;
        }
    }
}
