package com.tyron.builder.dexing;

import com.android.SdkConstants;

import org.gradle.util.internal.GFileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Directory representing a dex archive. All dex entries, {@link DexArchiveEntry}, are stored under
 * the directory {@link #getRootPath()}
 */
final class DirDexArchive implements DexArchive {

    @NotNull
    private final Path rootDir;

    public DirDexArchive(@NotNull Path rootDir) {
        this.rootDir = rootDir;
    }

    @NotNull
    @Override
    public Path getRootPath() {
        return rootDir;
    }

    @Override
    public void addFile(@NotNull String relativePath, byte[] bytes, int offset, int end)
            throws IOException {
        Path finalPath = rootDir.resolve(relativePath);
        Files.createDirectories(finalPath.getParent());
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(finalPath))) {
            os.write(bytes, offset, end);
            os.flush();
        }
    }

    @Override
    @NotNull
    public List<DexArchiveEntry> getSortedDexArchiveEntries() {
        List<Path> dexFiles =
                DexUtilsKt.getSortedFilesInDir(
                        rootDir,
                        relativePath ->
                                relativePath
                                        .toLowerCase(Locale.ENGLISH)
                                        .endsWith(SdkConstants.DOT_DEX));
        List<DexArchiveEntry> dexArchiveEntries = new ArrayList<>(dexFiles.size());
        for (Path dexFile : dexFiles) {
            dexArchiveEntries.add(createEntry(dexFile));
        }
        return dexArchiveEntries;
    }

    @Override
    public void close() {
        // do nothing
    }

    private DexArchiveEntry createEntry(@NotNull Path dexFile) {
        byte[] content;
        try {
            content = Files.readAllBytes(dexFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path relativePath = getRootPath().relativize(dexFile);

        return new DexArchiveEntry(content, GFileUtils.toSystemIndependentPath(relativePath.toString()), this);
    }
}