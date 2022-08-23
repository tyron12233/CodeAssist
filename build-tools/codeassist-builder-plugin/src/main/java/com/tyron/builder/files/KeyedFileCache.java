package com.tyron.builder.files;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import org.gradle.util.internal.GFileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.function.Function;

/**
 * File cache that stored files based on the given key function. The general contract of the {@code
 * KeyedFileCache} is that files are stored and can be later retrieved using the file to derive a
 * unique key using the given key function. For example:
 *
 * <pre>
 * File cacheDir = ... // some directory.
 * KeyedFileCache cache = new KeyedFileCache(cacheDir, KeyedFileCache::fileNameKey);
 *
 * File a = new File(...); // some file in the filesystem.
 * cache.add(a);
 *
 * // Modify file "a".
 * File b = cache.get(a); // "b" will be a different file whose
 *                        // contents are those of "a" before
 *                        // being modified.
 * </pre>
 * <p>
 * A custom API for zip files ({@link #add(ZipCentralDirectory)} allows to only store a zip file's
 * Central Directory Record as this is generally only what is needed from the cache.
 */
public class KeyedFileCache {

    /**
     * The directory where the cache exists.
     */
    @NotNull
    private final File directory;

    /**
     * The function that maps a file to its location in the cache. See {@link #fileNameKey(File)}
     * for one example.
     */
    @NotNull
    private final Function<File, String> keyFunction;

    /**
     * Creates a new cache.
     *
     * @param directory   the directory where the cache is stored
     * @param keyFunction a function that maps a file to its location in the cache. See {@link
     *                    #fileNameKey(File)} for one example.
     */
    public KeyedFileCache(@NotNull File directory, @NotNull Function<File, String> keyFunction) {
        Preconditions.checkArgument(directory.isDirectory(), "!File.isDirectory(): %s", directory);
        this.directory = directory;
        this.keyFunction = keyFunction;
    }

    /**
     * Adds a file to the cache, replacing any file that had the exact same absolute path.
     *
     * @param f the file to add
     * @throws IOException failed to copy the file into the cache
     */
    public void add(@NotNull File f) throws IOException {
        Preconditions.checkArgument(f.isFile(), "!File.isFile(): %s", f);

        if (!directory.isDirectory()) {
            GFileUtils.mkdirs(directory);
        }

        String k = key(f);
        Files.copy(f, new File(directory, k));
    }

    /**
     * Adds a file to the cache, replacing any file that had the exact same absolute path.
     *
     * @param centralDirectory the file to add
     * @throws IOException failed to copy the file into the cache
     */
    public void add(@NotNull ZipCentralDirectory centralDirectory) throws IOException {
        final File file = centralDirectory.getFile();
        Preconditions.checkArgument(file.isFile(), "!File.isFile(): %s", file);

        if (!directory.isDirectory()) {
            GFileUtils.mkdirs(directory);
        }

        centralDirectory.writeTo(new File(directory, key(file)));
    }

    /**
     * Obtains the cached file corresponding to the file with the given path.
     *
     * @param f the path
     * @return the cached file, {@code null} if there is no file in the cache that corresponds to
     * the given file
     */
    @Nullable
    public File get(@NotNull File f) {
        File file = new File(directory, key(f));
        if (file.isFile()) {
            return file;
        } else {
            return null;
        }
    }

    /**
     * Removes any cached version of the given path.
     *
     * @param f the path
     * @throws IOException failed to remove the file
     */
    public void remove(@NotNull File f) throws IOException {
        File toRemove = new File(directory, key(f));
        if (toRemove.exists()) {
            GFileUtils.forceDelete(toRemove);
        }
    }

    private String key(@NotNull File f) {
        String key = keyFunction.apply(f);
        if (key != null) {
            return key;
        }
        throw new IllegalStateException("No key found for file " + f);
    }

    /**
     * Computes a unique key identifying the path of the file.
     *
     * <p>WARNING: this is dangerous to use with normalized gradle inputs that discard the absolute
     * path of files.
     *
     * @param f the path
     * @return the unique key
     */
    @NotNull
    public static String fileNameKey(@NotNull File f) {
        String absolutePath = f.getAbsolutePath();
        byte[] sha1Sum = Hashing.sha1().hashString(absolutePath, Charsets.UTF_8).asBytes();
        return new String(Base64.getEncoder().encode(sha1Sum), Charsets.US_ASCII).replaceAll("/", "_");
    }

    /**
     * Clears the cache.
     *
     * @throws IOException failed to clear the cache
     */
    public void clear() throws IOException {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File f : files) {
            if (f.isFile()) {
                GFileUtils.forceDelete(f);
            }
        }
    }
}