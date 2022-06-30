package com.tyron.builder.internal.classpath;

import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.internal.file.FileException;
import com.tyron.builder.internal.file.FileMetadata;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.file.Stat;
import com.tyron.builder.internal.file.archive.ZipEntry;
import com.tyron.builder.internal.file.archive.ZipInput;
import com.tyron.builder.internal.file.archive.impl.FileZipInput;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Allows the classes and resources of a classpath element such as a jar or directory to be visited.
 */
@ServiceScope(Scopes.UserHome.class)
public class ClasspathWalker {
    private final Stat stat;

    public ClasspathWalker(Stat stat) {
        this.stat = stat;
    }

    /**
     * Visits the entries of the given classpath element.
     *
     * @throws FileException On failure to open a Jar file.
     */
    public void visit(File root, ClasspathEntryVisitor visitor) throws IOException, FileException {
        FileMetadata fileMetadata = stat.stat(root);
        if (fileMetadata.getType() == FileType.RegularFile) {
            visitJarContents(root, visitor);
        } else if (fileMetadata.getType() == FileType.Directory) {
            visitDirectoryContents(root, visitor);
        }
    }

    private void visitDirectoryContents(File dir, ClasspathEntryVisitor visitor) throws IOException {
        visitDir(dir, "", visitor);
    }

    private void visitDir(File dir, String prefix, ClasspathEntryVisitor visitor) throws IOException {
        File[] files = dir.listFiles();

        // Apply a consistent order, regardless of file system ordering
        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            FileMetadata fileMetadata = stat.stat(file);
            if (fileMetadata.getType() == FileType.RegularFile) {
                visitFile(file, prefix + file.getName(), visitor);
            } else if (fileMetadata.getType() == FileType.Directory) {
                visitDir(file, prefix + file.getName() + "/", visitor);
            }
        }
    }

    private void visitFile(File file, String name, ClasspathEntryVisitor visitor) throws IOException {
        visitor.visit(new FileEntry(name, file));
    }

    private void visitJarContents(File jarFile, ClasspathEntryVisitor visitor) throws IOException {
        try (ZipInput entries = FileZipInput.create(jarFile)) {
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
                visitor.visit(new ZipClasspathEntry(entry));
            }
        }
    }

    private static class ZipClasspathEntry implements ClasspathEntryVisitor.Entry {
        private final ZipEntry entry;

        public ZipClasspathEntry(ZipEntry entry) {
            this.entry = entry;
        }

        @Override
        public String getName() {
            return entry.getName();
        }

        @Override
        public RelativePath getPath() {
            return RelativePath.parse(false, getName());
        }

        @Override
        public byte[] getContent() throws IOException {
            return entry.getContent();
        }
    }

    private static class FileEntry implements ClasspathEntryVisitor.Entry {
        private final String name;
        private final File file;

        public FileEntry(String name, File file) {
            this.name = name;
            this.file = file;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public RelativePath getPath() {
            return RelativePath.parse(false, name);
        }

        @Override
        public byte[] getContent() throws IOException {
            return Files.readAllBytes(file.toPath());
        }
    }
}
