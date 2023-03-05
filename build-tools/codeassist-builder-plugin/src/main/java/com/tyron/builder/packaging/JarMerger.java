package com.tyron.builder.packaging;

import com.google.common.collect.ImmutableSortedMap;
import com.android.SdkConstants;

import org.gradle.util.internal.GFileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Jar Merger class. */
public class JarMerger implements JarCreator {

    public static final Predicate<String> CLASSES_ONLY =
            archivePath -> archivePath.endsWith(SdkConstants.DOT_CLASS);
    public static final Predicate<String> EXCLUDE_CLASSES =
            archivePath -> !archivePath.endsWith(SdkConstants.DOT_CLASS);

    /**
     * A filter that keeps everything but ignores duplicate resources.
     *
     * <p>Stateful, hence a factory method rather than an instance.
     */
    public static Predicate<String> allIgnoringDuplicateResources() {
        // Keep track of resources to avoid failing on collisions.
        Set<String> resources = new HashSet<>();
        return archivePath ->
                archivePath.endsWith(SdkConstants.DOT_CLASS) || resources.add(archivePath);
    }

    public static final String MODULE_PATH = "module-path";



    public static final FileTime ZERO_TIME = FileTime.fromMillis(0);

    private final byte[] buffer = new byte[8192];

    @NotNull
    private final JarOutputStream jarOutputStream;

    @Nullable private final Predicate<String> filter;

    public JarMerger(@NotNull Path jarFile) throws IOException {
        this(jarFile, null);
    }

    public JarMerger(@NotNull Path jarFile, @Nullable Predicate<String> filter) throws IOException {
        this.filter = filter;
        Files.createDirectories(jarFile.getParent());
        jarOutputStream =
                new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(jarFile)));
    }

    @Override
    public void addDirectory(@NotNull Path directory) throws IOException {
        addDirectory(directory, filter, null, null);
    }

    @Override
    public void addDirectory(
            @NotNull Path directory,
            @Nullable Predicate<String> filterOverride,
            @Nullable Transformer transformer,
            @Nullable Relocator relocator)
            throws IOException {
        ImmutableSortedMap.Builder<String, Path> candidateFiles = ImmutableSortedMap.naturalOrder();
        Files.walkFileTree(
                directory,
                EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String entryPath =
                                GFileUtils.toSystemIndependentPath(directory.relativize(file).toString());
                        if (filterOverride != null && !filterOverride.test(entryPath)) {
                            return FileVisitResult.CONTINUE;
                        }

                        if (relocator != null) {
                            entryPath = relocator.relocate(entryPath);
                        }

                        candidateFiles.put(entryPath, file);
                        return FileVisitResult.CONTINUE;
                    }
                });
        ImmutableSortedMap<String, Path> sortedFiles = candidateFiles.build();
        for (Map.Entry<String, Path> entry : sortedFiles.entrySet()) {
            String entryPath = entry.getKey();
            try (InputStream is = new BufferedInputStream(Files.newInputStream(entry.getValue()))) {
                if (transformer != null) {
                    @Nullable InputStream is2 = transformer.filter(entryPath, is);
                    if (is2 != null) {
                        write(new JarEntry(entryPath), is2);
                    }
                } else {
                    write(new JarEntry(entryPath), is);
                }
            }
        }
    }

    @Override
    public void addJar(@NotNull Path file) throws IOException {
        addJar(file, filter, null);
    }

    @Override
    public void addJar(
            @NotNull Path file,
            @Nullable Predicate<String> filterOverride,
            @Nullable Relocator relocator)
            throws IOException {
        try (BufferedInputStream inputStream =
                new BufferedInputStream(Files.newInputStream(file))) {
            addJar(inputStream, filterOverride, relocator);
        }
    }

    public void addJar(@NotNull InputStream inputStream) throws IOException {
        addJar(inputStream, filter, null);
    }

    public void addJar(
            @NotNull InputStream inputStream,
            @Nullable Predicate<String> filterOverride,
            @Nullable Relocator relocator)
            throws IOException {

        try (ZipInputStream zis = new ZipInputStream(inputStream)) {

            // loop on the entries of the jar file package and put them in the final jar
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // do not take directories
                if (entry.isDirectory()) {
                    continue;
                }

                // Filter out files, e.g. META-INF folder, not classes.
                String name = entry.getName();
                if (filterOverride != null && !filterOverride.test(name)) {
                    continue;
                }

                if (relocator != null) {
                    name = relocator.relocate(name);
                }

                if (name.contains("../")) {
                    throw new InvalidPathException(name, "Entry name contains invalid characters");
                }
                JarEntry newEntry = new JarEntry(name);
                newEntry.setMethod(entry.getMethod());
                if (newEntry.getMethod() == ZipEntry.STORED) {
                    newEntry.setSize(entry.getSize());
                    newEntry.setCompressedSize(entry.getCompressedSize());
                    newEntry.setCrc(entry.getCrc());
                }
                newEntry.setLastModifiedTime(ZERO_TIME);

                // read the content of the entry from the input stream, and write it into the
                // archive.
                write(newEntry, zis);
            }
        }
    }

    @Override
    public void addFile(@NotNull String entryPath, @NotNull Path file) throws IOException {
        try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
            write(new JarEntry(entryPath), is);
        }
    }

    @Override
    public void addEntry(@NotNull String entryPath, @NotNull InputStream input) throws IOException {
        try (InputStream is = new BufferedInputStream(input)) {
            write(new JarEntry(entryPath), is);
        }
    }

    /**
     * Change the compression level for the next entries added to this jar. See {@link
     * ZipOutputStream#setLevel(int)} for more details.
     *
     * <p>Use 0 for no compression.
     *
     * @param level the compression level (0-9)
     */
    @Override
    public void setCompressionLevel(int level) {
        jarOutputStream.setLevel(level);
    }

    @Override
    public void close() throws IOException {
        jarOutputStream.close();
    }

    @Override
    public void setManifestProperties(Map<String, String> properties) throws IOException {
        Manifest manifest = new Manifest();
        Attributes global = manifest.getMainAttributes();
        global.put(Attributes.Name.MANIFEST_VERSION, "1.0.0");
        properties.forEach(
                (attributeName, attributeValue) ->
                        global.put(new Attributes.Name(attributeName), attributeValue));
        JarEntry manifestEntry = new JarEntry(JarFile.MANIFEST_NAME);
        setEntryAttributes(manifestEntry);
        jarOutputStream.putNextEntry(manifestEntry);
        try {
            manifest.write(jarOutputStream);
        } finally {
            jarOutputStream.closeEntry();
        }
    }

    private void write(@NotNull JarEntry entry, @NotNull InputStream from) throws IOException {
        setEntryAttributes(entry);
        jarOutputStream.putNextEntry(entry);
        int count;
        while ((count = from.read(buffer)) != -1) {
            jarOutputStream.write(buffer, 0, count);
        }
        jarOutputStream.closeEntry();
    }

    private void setEntryAttributes(@NotNull JarEntry entry) {
        entry.setLastModifiedTime(ZERO_TIME);
        entry.setLastAccessTime(ZERO_TIME);
        entry.setCreationTime(ZERO_TIME);
    }
}