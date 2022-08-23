package com.tyron.builder.files;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import org.gradle.util.internal.GFileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import kotlin.io.FilesKt;

/**
 * Representation of a file with respect to a base directory. A {@link RelativeFile} contains
 * information on the file, the base directory and the relative path from the base directory to
 * the file. The relative path is kept in OS independent form with sub directories separated by
 * slashes.
 *
 * <p>Neither the file nor the base need to exist. They are treated as abstract paths.
 */
public class RelativeFile {

    public enum Type {
        DIRECTORY,
        JAR,
    }

    /** For {@link Type#JAR}, the jar. For {@link Type#DIRECTORY} the file within the directory. */
    @NotNull
    private final File file;

    /** The OS independent path from base to file, including the file name in the end. */
    @NotNull private final String relativePath;

    @NotNull public final Type type;

    /**
     * Creates a new relative file.
     *
     * @param base the base directory.
     * @param file the file, must not be the same as the base directory and must be located inside
     *     {@code base}
     */
    public RelativeFile(@NotNull File base, @NotNull File file) {
        this(
                file,
                GFileUtils.toSystemIndependentPath(FilesKt.toRelativeString(file, base)),
                Type.DIRECTORY);

        Preconditions.checkArgument(
                !base.equals(file), "Base must not equal file. Given: %s", base.getAbsolutePath());
    }

    /**
     * Creates a new relative file.
     *
     * @param base the base jar.
     * @param relativePath the relative path to the file.
     */
    public RelativeFile(@NotNull File base, @NotNull String relativePath) {
        this(base, relativePath, Type.JAR);
    }

    public static RelativeFile fileInDirectory(@NotNull String relativePath, @NotNull File file) {
        return new RelativeFile(file, relativePath, Type.DIRECTORY);
    }

    /**
     * Creates a new relative file.
     *
     * @param file the base jar, or the file within the directory.
     * @param relativePath the relative path to the file.
     * @param type the type of the base.
     */
    private RelativeFile(@NotNull File file, @NotNull String relativePath, @NotNull Type type) {
        Preconditions.checkArgument(!relativePath.isEmpty(), "Relative path cannot be empty");
        this.file = file;
        this.relativePath = relativePath;
        this.type = type;
    }

    /**
     * Obtains the base directory or jar.
     *
     * <p>Only applicable when {@link #getType()} == {@link Type#JAR}
     *
     * @return the base directory or jar as provided when created the object
     */
    @NotNull
    public File getBase() {
        Preconditions.checkState(getType() == Type.JAR, "Only applicable for jars");
        return file;
    }

    /**
     * Obtains the OS independent path. The general contract of the normalized relative path is that
     * by replacing the slashes by file separators in the relative path and appending it to the base
     * directory's path, the resulting path is the file's path
     *
     * @return the normalized path, separated by slashes; directories have a terminating slash
     */
    @NotNull
    public String getRelativePath() {
        return relativePath;
    }

    @NotNull
    public Type getType() {
        return type;
    }

    /**
     * Returns the actual file from the directory.
     *
     * <p>Only applicable when {@link #getType()} == {@link Type#DIRECTORY}
     *
     * @return
     */
    @NotNull
    public File getFile() {
        Preconditions.checkState(getType() == Type.DIRECTORY, "Only applicable for directories");
        return file;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(file, relativePath);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof RelativeFile)) {
            return false;
        }

        RelativeFile other = (RelativeFile) obj;
        return Objects.equal(file, other.file)
                && Objects.equal(relativePath, other.relativePath)
                && Objects.equal(type, other.type);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("base", file)
                .add("path", relativePath)
                .add("type", type)
                .toString();
    }
}