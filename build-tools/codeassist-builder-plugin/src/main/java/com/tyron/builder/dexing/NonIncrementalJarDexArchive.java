package com.tyron.builder.dexing;

import com.google.common.base.Preconditions;
import com.android.SdkConstants;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/** Implementation of the {@link DexArchive} that does not support incremental updates. */
final class NonIncrementalJarDexArchive implements DexArchive {

    private static final FileTime ZERO_TIME = FileTime.fromMillis(0);

    @NotNull
    private final Path targetPath;
    @Nullable
    private JarOutputStream jarOutputStream; // null if this jar is for reading

    public NonIncrementalJarDexArchive(@NotNull Path targetPath) throws IOException {
        this.targetPath = targetPath;
        if (Files.isRegularFile(targetPath)) {
            // we should read this file
            this.jarOutputStream = null;
        } else {
            // we are creating this file
            this.jarOutputStream =
                    new JarOutputStream(
                            new BufferedOutputStream(
                                    Files.newOutputStream(
                                            targetPath,
                                            StandardOpenOption.WRITE,
                                            StandardOpenOption.CREATE_NEW)));
        }
    }

    @NotNull
    @Override
    public Path getRootPath() {
        return targetPath;
    }

    @Override
    public void addFile(@NotNull String relativePath, byte[] bytes, int offset, int end)
            throws IOException {
        Preconditions.checkNotNull(jarOutputStream, "Archive is not writeable : %s", targetPath);
        // Need to pre-compute checksum for STORED (uncompressed) entries)
        CRC32 checksum = new CRC32();
        checksum.update(bytes, offset, end);

        ZipEntry zipEntry = new ZipEntry(relativePath);
        zipEntry.setLastModifiedTime(ZERO_TIME);
        zipEntry.setLastAccessTime(ZERO_TIME);
        zipEntry.setCreationTime(ZERO_TIME);
        zipEntry.setCrc(checksum.getValue());
        zipEntry.setSize(end - offset);
        zipEntry.setCompressedSize(end - offset);
        zipEntry.setMethod(ZipEntry.STORED);

        jarOutputStream.putNextEntry(zipEntry);
        jarOutputStream.write(bytes, offset, end);
        jarOutputStream.flush();
        jarOutputStream.closeEntry();
    }

    @NotNull
    @Override
    public List<DexArchiveEntry> getSortedDexArchiveEntries() {
        Preconditions.checkState(
                jarOutputStream == null, "Archive is not for reading: %s", targetPath);

        SortedMap<String, byte[]> dexEntries =
                DexUtilsKt.getSortedRelativePathsInJarWithContents(
                        targetPath.toFile(),
                        relativePath ->
                                relativePath
                                        .toLowerCase(Locale.ENGLISH)
                                        .endsWith(SdkConstants.DOT_DEX));
        List<DexArchiveEntry> dexArchiveEntries = new ArrayList<>(dexEntries.size());
        for (String relativePath : dexEntries.keySet()) {
            dexArchiveEntries.add(
                    new DexArchiveEntry(dexEntries.get(relativePath), relativePath, this));
        }
        return dexArchiveEntries;
    }

    @Override
    public void close() throws IOException {
        if (jarOutputStream != null) {
            jarOutputStream.flush();
            jarOutputStream.close();
        }
    }
}