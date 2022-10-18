package com.tyron.builder.dexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class JarClassFileInput implements ClassFileInput {

    /** If we are unable to read .class files from the input. */
    public static final class JarClassFileInputsException extends RuntimeException {

        public JarClassFileInputsException(@NotNull String s, @NotNull IOException e) {
            super(s, e);
        }
    }

    @NotNull private final Path rootPath;
    @Nullable
    private ZipFile jarFile;

    public JarClassFileInput(@NotNull Path rootPath) {
        this.rootPath = rootPath;
    }

    @Override
    public void close() throws IOException {
        if (jarFile != null) {
            jarFile.close();
        }
    }

    @Override
    @NotNull
    public Stream<ClassFileEntry> entries(BiPredicate<Path, String> filter) {
        if (jarFile == null) {
            try {
                jarFile = new ZipFile(rootPath.toFile());
            } catch (IOException e) {
                throw new JarClassFileInputsException(
                        "Unable to read jar file " + rootPath.toString(), e);
            }
        }

        List<ZipEntry> entryList = new ArrayList<>(jarFile.size());
        Enumeration<? extends ZipEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry zipEntry = entries.nextElement();
            if (CLASS_MATCHER.test(zipEntry.getName())
                    && filter.test(rootPath, zipEntry.getName())) {
                entryList.add(zipEntry);
            }
        }

        return entryList.stream().map(this::createEntryFromEntry);
    }

    @Override
    public Path getPath() {
        return rootPath;
    }

    @NotNull
    private ClassFileEntry createEntryFromEntry(@NotNull ZipEntry entry) {
        return new NoCacheJarClassFileEntry(entry, Objects.requireNonNull(jarFile), this);
    }
}