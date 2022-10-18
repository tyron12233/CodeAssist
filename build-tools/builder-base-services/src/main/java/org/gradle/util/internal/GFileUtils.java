package org.gradle.util.internal;

import static org.apache.commons.io.FilenameUtils.removeExtension;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.UncheckedIOException;
import org.gradle.internal.IoActions;
import org.gradle.util.GUtil;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.Checksum;

public class GFileUtils {
    private static final Joiner PATH_JOINER = Joiner.on(File.separatorChar);

    public static String readFile(File file) {
        return GUtil.uncheckedCall(() -> FileUtils.readFileToString(file, StandardCharsets.UTF_8));
    }

    public static void forceDelete(File file) {
        try {
            FileUtils.forceDelete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void deleteQuietly(File file) {
        FileUtils.deleteQuietly(file);
    }

    /**
     * Ensures that the given file (or directory) is marked as modified. If the file
     * (or directory) does not exist, a new file is created.
     */
    public static void touch(File file) {
        try {
            if (!file.createNewFile()) {
                touchExisting(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Ensures that the given file (or directory) is marked as modified. The file
     * (or directory) must exist.
     */
    public static void touchExisting(File file) {
        try {
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            if (file.isFile() && file.length() == 0) {
                // On Linux, users cannot touch files they don't own but have write access to
                // because the JDK uses futimes() instead of futimens() [note the 'n'!]
                // see https://github.com/gradle/gradle/issues/7873
                touchFileByWritingEmptyByteArray(file);
            } else {
                throw new UncheckedIOException("Could not update timestamp for " + file, e);
            }
        }
    }

    private static void touchFileByWritingEmptyByteArray(File file) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not update timestamp for " + file, e);
        }
    }

    /**
     * Like {@link java.io.File#mkdirs()}, except throws an informative error if a dir cannot be created.
     *
     * @param dir The dir to create, including any non existent parent dirs.
     */
    public static void mkdirs(File dir) {
        dir = dir.getAbsoluteFile();
        if (dir.isDirectory()) {
            return;
        }

        if (dir.exists() && !dir.isDirectory()) {
            throw new UncheckedIOException(String.format("Cannot create directory '%s' as it already exists, but is not a directory", dir));
        }

        List<File> toCreate = new LinkedList<File>();
        File parent = dir.getParentFile();
        while (!parent.exists()) {
            toCreate.add(parent);
            parent = parent.getParentFile();
        }

        Collections.reverse(toCreate);
        for (File parentDirToCreate : toCreate) {
            if (parentDirToCreate.isDirectory()) {
                continue;
            }

            File parentDirToCreateParent = parentDirToCreate.getParentFile();
            if (!parentDirToCreateParent.isDirectory()) {
                throw new UncheckedIOException(String.format("Cannot create parent directory '%s' when creating directory '%s' as '%s' is not a directory", parentDirToCreate, dir, parentDirToCreateParent));
            }

            if (!parentDirToCreate.mkdir() && !parentDirToCreate.isDirectory()) {
                throw new UncheckedIOException(String.format("Failed to create parent directory '%s' when creating directory '%s'", parentDirToCreate, dir));
            }
        }

        if (!dir.mkdir() && !dir.isDirectory()) {
            throw new UncheckedIOException(String.format("Failed to create directory '%s'", dir));
        }
    }

    /**
     * Canonicalizes the given file.
     */
    public static File canonicalize(File src) {
        try {
            return src.getCanonicalFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void moveFile(File source, File destination) {
        try {
            FileUtils.moveFile(source, destination);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void moveExistingFile(File source, File destination) {
        boolean rename = source.renameTo(destination);
        if (!rename) {
            moveFile(source, destination);
        }
    }

    /**
     * If the destination file exists, then this method will overwrite it.
     *
     * @see FileUtils#copyFile(File, File)
     */
    public static void copyFile(File source, File destination) {
        try {
            FileUtils.copyFile(source, destination);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void copyDirectory(File source, File destination) {
        try {
            FileUtils.copyDirectory(source, destination);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void moveDirectory(File source, File destination) {
        try {
            FileUtils.moveDirectory(source, destination);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void moveExistingDirectory(File source, File destination) {
        boolean rename = source.renameTo(destination);
        if (!rename) {
            moveDirectory(source, destination);
        }
    }

    public static Checksum checksum(File file, Checksum checksum) {
        try {
            return FileUtils.checksum(file, checksum);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Makes the parent directory of the file, and any non existent parents.
     *
     * @param child The file to create the parent dir for
     * @return The parent dir file
     * @see #mkdirs(java.io.File)
     */
    public static File parentMkdirs(File child) {
        File parent = child.getParentFile();
        mkdirs(parent);
        return parent;
    }

    public static void deleteIfExists(File file) {
        if (!file.exists()) {
            return;
        }

        forceDelete(file);
    }

    /**
     * Normalizes the given file, removing redundant segments like /../. If normalization
     * tries to step beyond the file system root, the root is returned.
     */
    public static File normalize(File src) {
        String path = src.getAbsolutePath();
        String normalizedPath = FilenameUtils.normalizeNoEndSeparator(path);
        if (normalizedPath != null) {
            return new File(normalizedPath);
        }
        File root = src;
        File parent = root.getParentFile();
        while (parent != null) {
            root = root.getParentFile();
            parent = root.getParentFile();
        }
        return root;
    }

    public static String readFileToString(File sourceFile) {
        return GUtil.uncheckedCall(() -> FileUtils.readFileToString(sourceFile, StandardCharsets.UTF_8));
    }

    public static boolean hasExtensionIgnoresCase(String name, String extension) {
        if (extension.startsWith(".")) {
            extension = extension.substring(1);
        }
        return FilenameUtils.getExtension(name).equalsIgnoreCase(extension);
    }

    public static String withExtension(String filePath, String extension) {
        if (filePath.toLowerCase().endsWith(extension)) {
            return filePath;
        }
        return removeExtension(filePath) + extension;
    }

    public static void copyURLToFile(URL source, File destination) {
        try {
            FileUtils.copyURLToFile(source, destination);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeFile(String content, File file, String s) {
        try {
            FileUtils.writeStringToFile(file, content, s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Converts a string into a string that is safe to use as a file name. The result will only include ascii characters and numbers, and the "-","_", #, $ and "." characters.
     */
    public static String toSafeFileName(String name) {
        int size = name.length();
        StringBuilder rc = new StringBuilder(size * 2);
        for (int i = 0; i < size; i++) {
            char c = name.charAt(i);
            boolean valid = c >= 'a' && c <= 'z';
            valid = valid || (c >= 'A' && c <= 'Z');
            valid = valid || (c >= '0' && c <= '9');
            valid = valid || (c == '_') || (c == '-') || (c == '.') || (c == '$');
            if (valid) {
                rc.append(c);
            } else {
                // Encode the character using hex notation
                rc.append('#');
                rc.append(Integer.toHexString(c));
            }
        }
        return rc.toString();
    }

    /**
     * Checks if the given file path ends with the given extension.
     * @param file the file
     * @param extension candidate extension including leading dot
     * @return true if {@code file.getPath().endsWith(extension)}
     */
    public static boolean hasExtension(File file, String extension) {
        return file.getPath().endsWith(extension);
    }

    public static List<String> toPaths(List<File> files) {
        return files.stream().map(File::getAbsolutePath).collect(Collectors.toList());
    }

    /**
     * Returns the tail of a file.
     *
     * @param file to read from tail
     * @param maxLines max lines to read
     * @return tail content
     * @throws TailReadingException when reading failed
     */
    public static String tail(File file, int maxLines) throws TailReadingException {
        BufferedReader reader = null;
        FileReader fileReader = null;
        try {
            fileReader = new FileReader(file);
            reader = new BufferedReader(fileReader);

            LimitedDescription description = new LimitedDescription(maxLines);
            String line = reader.readLine();
            while (line != null) {
                description.append(line);
                line = reader.readLine();
            }
            return description.toString();
        } catch (Exception e) {
            throw new TailReadingException(e);
        } finally {
            IoActions.closeQuietly(fileReader);
            IoActions.closeQuietly(reader);
        }
    }

    /**
     * Converts a system-dependent path into a /-based path.
     *
     * @param path the system dependent path
     * @return the system independent path
     */
    @NotNull
    public static String toSystemIndependentPath(@NotNull String path) {
        if (File.separatorChar != '/') {
            path = path.replace(File.separatorChar, '/');
        }
        return path;
    }

    /**
     * Makes sure {@code path} is an empty directory. If {@code path} is a directory, its contents
     * are removed recursively, leaving an empty directory. If {@code path} is not a directory,
     * it is removed and a directory created with the given path. If {@code path} does not
     * exist, a directory is created with the given path.
     *
     * @param path the path, that may exist or not and may be a file or directory
     * @throws IOException failed to delete directory contents, failed to delete {@code path} or
     * failed to create a directory at {@code path}
     */
    public static void cleanOutputDir(@NotNull File path) throws IOException {
        if (!path.isDirectory()) {
            if (path.exists()) {
                deleteIfExists(path);
            }

            if (!path.mkdirs()) {
                throw new IOException(String.format("Could not create empty folder %s", path));
            }

            return;
        }

        deleteDirectoryContents(path);
    }

    /**
     * Recursively deletes a directory content (including the sub directories) but not itself.
     *
     * @param directory the directory, that must exist and be a valid directory
     * @throws IOException failed to delete the file / directory
     */
    public static void deleteDirectoryContents(@NotNull final File directory) throws IOException {
        Preconditions.checkArgument(directory.isDirectory(), "!directory.isDirectory");

        File[] files = directory.listFiles();
        Preconditions.checkNotNull(files);
        for (File file : files) {
            deleteIfExists(file);
        }
    }

    /**
     * Joins a list of path segments to a given File object.
     *
     * @param dir the file object.
     * @param paths the segments.
     * @return a new File object.
     */
    @NotNull
    public static File join(@NotNull File dir, @NotNull String... paths) {
        if (paths.length == 0) {
            return dir;
        }

        return new File(dir, PATH_JOINER.join(paths));
    }

    /**
     * Joins a list of path segments to a given File object.
     *
     * @param dir the file object.
     * @param paths the segments.
     * @return a new File object.
     */
    @NotNull
    public static File join(@NotNull File dir, @NotNull Iterable<String> paths) {
        return new File(dir, PATH_JOINER.join(removeEmpty(paths)));
    }

    /**
     * Joins a set of segment into a string, separating each segments with a host-specific
     * path separator.
     *
     * @param paths the segments.
     * @return a string with the segments.
     */
    @NotNull
    public static String join(@NotNull String... paths) {
        return PATH_JOINER.join(removeEmpty(Lists.newArrayList(paths)));
    }

    /**
     * Joins a set of segment into a string, separating each segments with a host-specific
     * path separator.
     *
     * @param paths the segments.
     * @return a string with the segments.
     */
    @NotNull
    public static String join(@NotNull Iterable<String> paths) {
        return PATH_JOINER.join(paths);
    }

    private static Iterable<String> removeEmpty(Iterable<String> input) {
        return Lists.newArrayList(input)
                .stream()
                .filter(it -> !it.isEmpty())
                .collect(Collectors.toList());
    }

    @NotNull
    public static String toSystemDependentPath(@NotNull String path) {
        if (File.separatorChar != '/') {
            path = path.replace('/', File.separatorChar);
        }
        return path;
    }

    /**
     * Returns the path of target relative to base.
     *
     * @param target target file or directory
     * @param base base directory
     * @return the path of target relative to base.
     */
    public static String relativePathOf(File target, File base) {
        String separatorChars = "/" + File.separator;
        List<String> basePath = splitAbsolutePathOf(base, separatorChars);
        List<String> targetPath = new ArrayList<String>(splitAbsolutePathOf(target, separatorChars));

        // Find and remove common prefix
        int maxDepth = Math.min(basePath.size(), targetPath.size());
        int prefixLen = 0;
        while (prefixLen < maxDepth && basePath.get(prefixLen).equals(targetPath.get(prefixLen))) {
            prefixLen++;
        }
        basePath = basePath.subList(prefixLen, basePath.size());
        targetPath = targetPath.subList(prefixLen, targetPath.size());

        for (int i = 0; i < basePath.size(); i++) {
            targetPath.add(0, "..");
        }
        if (targetPath.isEmpty()) {
            return ".";
        }
        return CollectionUtils.join(File.separator, targetPath);
    }

    private static List<String> splitAbsolutePathOf(File baseDir, String separatorChars) {
        return Arrays.asList(StringUtils.split(baseDir.getAbsolutePath(), separatorChars));
    }

    public static class TailReadingException extends RuntimeException {
        public TailReadingException(Throwable throwable) {
            super(throwable);
        }
    }
}
