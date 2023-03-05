package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.build.apkzlib.zip.StoredEntry;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.android.utils.FileUtils;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Factory methods for {@link MergeOutputWriter}.
 */
public final class MergeOutputWriters {

    private MergeOutputWriters() {}

    /**
     * Creates a writer that writes files to a directory.
     *
     * @param directory the directory; will be created if it doesn't exist
     * @return the writer
     */
    @NonNull
    public static MergeOutputWriter toDirectory(@NonNull File directory) {

        /*
         * In theory we could just create the directory here. However, some tasks in gradle fail
         * if we create an empty directory for very obscure reasons. To avoid those errors, we
         * delay directory creation until it is really necessary.
         */
        Path directoryPath = directory.toPath();

        return new MergeOutputWriter() {

            /** Is the writer open? */
            private boolean isOpen = false;

            /** Have we ensured that the directory has been created? */
            private boolean created = false;

            @Override
            public void open() {
                Preconditions.checkState(!isOpen, "Writer already open");
                isOpen = true;
            }

            @Override
            public void close() {
                Preconditions.checkState(isOpen, "Writer closed");
                isOpen = false;
            }

            /**
             * Converts a path to the file, resolving it against the {@code directoryUri}.
             *
             * @param path the path
             * @return the resolved file
             */
            @NonNull
            private File toFile(@NonNull String path) {
                if (!created) {
                    FileUtils.mkdirs(directory);
                    created = true;
                }

                return directoryPath.resolve(path).toFile();
            }

            @Override
            public void remove(@NonNull String path) {
                Preconditions.checkState(isOpen, "Writer closed");

                File f = toFile(path);
                // it's possible that some folders only containing .class files got removed.
                // those were never merged in so we just ignore removing a non existent folder.
                if (!f.exists()) {
                    return;
                }

                // since we are notified of folders add/remove by the transform pipeline, handle
                // folders and files.
                if (f.isDirectory()) {
                    try {
                        FileUtils.deletePath(f);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return;
                }

                if (!f.delete()) {
                    throw new UncheckedIOException(
                            new IOException("Cannot delete file " + f.getAbsolutePath()));
                }

                for (File dir = f.getParentFile();
                        !dir.equals(directory);
                        dir = dir.getParentFile()) {
                    String[] names = dir.list();
                    assert names != null;
                    if (names.length == 0) {
                        try {
                            FileUtils.delete(dir);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    } else {
                        break;
                    }
                }
            }

            @Override
            public void create(@NonNull String path, @NonNull InputStream data, boolean compress) {
                Preconditions.checkState(isOpen, "Writer closed");

                File f = toFile(path);
                FileUtils.mkdirs(f.getParentFile());

                try (FileOutputStream fos = new FileOutputStream(f)) {
                    ByteStreams.copy(data, fos);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public void replace(@NonNull String path, @NonNull InputStream data, boolean compress) {
                Preconditions.checkState(isOpen, "Writer closed");

                File f = toFile(path);
                FileUtils.mkdirs(f.getParentFile());

                try (FileOutputStream fos = new FileOutputStream(f)) {
                    ByteStreams.copy(data, fos);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /**
     * Creates a writer that writes files to a zip file.
     *
     * @param file the existing zip file
     * @return the writer
     */
    @NonNull
    public static MergeOutputWriter toZip(@NonNull File file, @NonNull ZFileOptions zFileOptions) {
        return new MergeOutputWriter() {

            /** The open zip file, {@code null} if not open. */
            @Nullable private ZFile zipFile = null;

            @Override
            public void open() {
                Preconditions.checkState(zipFile == null, "Writer already open");

                try {
                    zipFile = ZFile.openReadWrite(file, zFileOptions);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public void close() {
                Preconditions.checkState(zipFile != null, "Writer not open");

                try {
                    zipFile.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    zipFile = null;
                }
            }

            @Override
            public void remove(@NonNull String path) {
                Preconditions.checkState(zipFile != null, "Writer not open");

                StoredEntry entry = zipFile.get(path);
                if (entry != null) {
                    try {
                        entry.delete();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }

            @Override
            public void create(@NonNull String path, @NonNull InputStream data, boolean compress) {
                Preconditions.checkState(zipFile != null, "Writer not open");

                try {
                    zipFile.add(path, data, compress);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public void replace(@NonNull String path, @NonNull InputStream data, boolean compress) {
                Preconditions.checkState(zipFile != null, "Writer not open");

                try {
                    zipFile.add(path, data, compress);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }
}