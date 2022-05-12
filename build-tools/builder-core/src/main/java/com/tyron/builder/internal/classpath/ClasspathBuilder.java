package com.tyron.builder.internal.classpath;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ServiceScope(Scopes.UserHome.class)
public class ClasspathBuilder {
    private static final int BUFFER_SIZE = 8192;
    private final TemporaryFileProvider temporaryFileProvider;

    @Inject
    ClasspathBuilder(final TemporaryFileProvider temporaryFileProvider) {
        this.temporaryFileProvider = temporaryFileProvider;
    }

    /**
     * Creates a Jar file using the given action to add entries to the file. If the file already exists it will be replaced.
     */
    public void jar(File jarFile, Action action) {
        try {
            buildJar(jarFile, action);
        } catch (Exception e) {
            throw new BuildException(String.format("Failed to create Jar file %s.", jarFile), e);
        }
    }

    private void buildJar(File jarFile, Action action) throws IOException {
        File parentDir = jarFile.getParentFile();
        File tmpFile = temporaryFileProvider.createTemporaryFile(jarFile.getName(), ".tmp");
        try {
            Files.createDirectories(parentDir.toPath());
            try (ZipOutputStream outputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmpFile), BUFFER_SIZE))) {
                outputStream.setLevel(0);
                action.execute(new ZipEntryBuilder(outputStream));
            }
            Files.move(tmpFile.toPath(), jarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmpFile.toPath());
        }
    }

    public interface Action {
        void execute(EntryBuilder builder) throws IOException;
    }

    public interface EntryBuilder {
        void put(String name, byte[] content) throws IOException;
    }

    private static class ZipEntryBuilder implements EntryBuilder {
        private final ZipOutputStream outputStream;
        private final Set<String> dirs = new HashSet<>();

        public ZipEntryBuilder(ZipOutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public void put(String name, byte[] content) throws IOException {
            maybeAddParent(name);
            ZipEntry zipEntry = newZipEntryWithFixedTime(name);
//            outputStream.setEncoding("UTF-8");
            outputStream.putNextEntry(zipEntry);
            outputStream.write(content);
            outputStream.closeEntry();
        }

        private void maybeAddParent(String name) throws IOException {
            String dir = dir(name);
            if (dir != null && dirs.add(dir)) {
                maybeAddParent(dir);
                ZipEntry zipEntry = newZipEntryWithFixedTime(dir);
                outputStream.putNextEntry(zipEntry);
                outputStream.closeEntry();
            }
        }

        @Nullable
        String dir(String name) {
            int pos = name.lastIndexOf('/');
            if (pos == name.length() - 1) {
                pos = name.lastIndexOf('/', pos - 1);
            }
            if (pos >= 0) {
                return name.substring(0, pos + 1);
            } else {
                return null;
            }
        }

        private ZipEntry newZipEntryWithFixedTime(String name) {
            ZipEntry entry = new ZipEntry(name);
//            entry.setTime(ZipCopyAction.CONSTANT_TIME_FOR_ZIP_ENTRIES);
            return entry;
        }
    }
}
