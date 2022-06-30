//package com.tyron.builder.api.internal.file.archive;
//
//import com.tyron.builder.api.BuildException;
//import com.tyron.builder.api.InvalidUserDataException;
//import com.tyron.builder.api.UncheckedIOException;
//import com.tyron.builder.api.file.FileVisitor;
//import com.tyron.builder.api.file.RelativePath;
//import com.tyron.builder.api.internal.file.Chmod;
//import com.tyron.builder.api.internal.file.collections.DirectoryFileTree;
//import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
//import com.tyron.builder.api.internal.file.collections.MinimalFileTree;
//import com.tyron.builder.internal.hash.FileHasher;
//import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
//import com.tyron.builder.api.provider.Provider;
//
//import java.io.File;
//
//import java.io.File;
//import java.io.IOException;
//import java.io.InputStream;
//import java.util.Enumeration;
//import java.util.Iterator;
//import java.util.Map;
//import java.util.TreeMap;
//import java.util.concurrent.atomic.AtomicBoolean;
//
//import static java.lang.String.format;
//
//public class ZipFileTree extends AbstractArchiveFileTree {
//    private final Provider<File> fileProvider;
//    private final File tmpDir;
//    private final Chmod chmod;
//    private final DirectoryFileTreeFactory directoryFileTreeFactory;
//    private final FileHasher fileHasher;
//
//    public ZipFileTree(
//            Provider<File> zipFile,
//            File tmpDir,
//            Chmod chmod,
//            DirectoryFileTreeFactory directoryFileTreeFactory,
//            FileHasher fileHasher
//    ) {
//        this.fileProvider = zipFile;
//        this.tmpDir = tmpDir;
//        this.chmod = chmod;
//        this.directoryFileTreeFactory = directoryFileTreeFactory;
//        this.fileHasher = fileHasher;
//    }
//
//    @Override
//    public String toString() {
//        return getDisplayName();
//    }
//
//    @Override
//    public String getDisplayName() {
//        return format("ZIP '%s'", fileProvider.getOrNull());
//    }
//
//    @Override
//    public DirectoryFileTree getMirror() {
//        return directoryFileTreeFactory.create(getExpandedDir());
//    }
//
//    @Override
//    public void visit(FileVisitor visitor) {
//        File zipFile = fileProvider.get();
//        if (!zipFile.exists()) {
//            throw new InvalidUserDataException(format("Cannot expand %s as it does not exist.", getDisplayName()));
//        }
//        if (!zipFile.isFile()) {
//            throw new InvalidUserDataException(format("Cannot expand %s as it is not a file.", getDisplayName()));
//        }
//
//        AtomicBoolean stopFlag = new AtomicBoolean();
//        File expandedDir = getExpandedDir();
//        try (ZipFile zip = new ZipFile(zipFile)) {
//            // The iteration order of zip.getEntries() is based on the hash of the zip entry. This isn't much use
//            // to us. So, collect the entries in a map and iterate over them in alphabetical order.
//            Iterator<ZipEntry> sortedEntries = entriesSortedByName(zip);
//            while (!stopFlag.get() && sortedEntries.hasNext()) {
//                ZipEntry entry = sortedEntries.next();
//                DetailsImpl details = new DetailsImpl(zipFile, expandedDir, entry, zip, stopFlag, chmod);
//                if (entry.isDirectory()) {
//                    visitor.visitDir(details);
//                } else {
//                    visitor.visitFile(details);
//                }
//            }
//        } catch (Exception e) {
//            throw new BuildException(format("Could not expand %s.", getDisplayName()), e);
//        }
//    }
//
//    private Iterator<ZipEntry> entriesSortedByName(ZipFile zip) {
//        Map<String, ZipEntry> entriesByName = new TreeMap<>();
//        Enumeration<ZipEntry> entries = zip.getEntries();
//        while (entries.hasMoreElements()) {
//            ZipEntry entry = entries.nextElement();
//            entriesByName.put(entry.getName(), entry);
//        }
//        return entriesByName.values().iterator();
//    }
//
//    @Override
//    public Provider<File> getBackingFileProvider() {
//        return fileProvider;
//    }
//
//    private File getExpandedDir() {
//        File zipFile = fileProvider.get();
//        String expandedDirName = zipFile.getName() + "_" + fileHasher.hash(zipFile);
//        return new File(tmpDir, expandedDirName);
//    }
//
//    private static class DetailsImpl extends AbstractFileTreeElement implements FileVisitDetails {
//        private final File originalFile;
//        private final File expandedDir;
//        private final ZipEntry entry;
//        private final ZipFile zip;
//        private final AtomicBoolean stopFlag;
//        private File file;
//
//        public DetailsImpl(File originalFile, File expandedDir, ZipEntry entry, ZipFile zip, AtomicBoolean stopFlag, Chmod chmod) {
//            super(chmod);
//            this.originalFile = originalFile;
//            this.expandedDir = expandedDir;
//            this.entry = entry;
//            this.zip = zip;
//            this.stopFlag = stopFlag;
//        }
//
//        @Override
//        public String getDisplayName() {
//            return format("zip entry %s!%s", originalFile, entry.getName());
//        }
//
//        @Override
//        public void stopVisiting() {
//            stopFlag.set(true);
//        }
//
//        @Override
//        public File getFile() {
//            if (file == null) {
//                file = new File(expandedDir, safeEntryName());
//                if (!file.exists()) {
//                    copyTo(file);
//                }
//            }
//            return file;
//        }
//
//        private String safeEntryName() {
//            return safeZipEntryName(entry.getName());
//        }
//
//        @Override
//        public long getLastModified() {
//            return entry.getTime();
//        }
//
//        @Override
//        public boolean isDirectory() {
//            return entry.isDirectory();
//        }
//
//        @Override
//        public long getSize() {
//            return entry.getSize();
//        }
//
//        @Override
//        public InputStream open() {
//            try {
//                return zip.getInputStream(entry);
//            } catch (IOException e) {
//                throw new UncheckedIOException(e);
//            }
//        }
//
//        @Override
//        public RelativePath getRelativePath() {
//            return new RelativePath(!entry.isDirectory(), safeEntryName().split("/"));
//        }
//
//        @Override
//        public int getMode() {
//            int unixMode = entry.getUnixMode() & 0777;
//            if (unixMode != 0) {
//                return unixMode;
//            }
//            //no mode infos available - fall back to defaults
//            return isDirectory()
//                    ? FileSystem.DEFAULT_DIR_MODE
//                    : FileSystem.DEFAULT_FILE_MODE;
//        }
//    }
//}
