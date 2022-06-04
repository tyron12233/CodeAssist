package com.tyron.builder.util.internal;

import static org.apache.commons.io.FilenameUtils.removeExtension;

import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.util.GUtil;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.Checksum;

public class GFileUtils {

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
}
