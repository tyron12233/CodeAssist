package com.tyron.builder.packaging;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Predicate;

public interface JarCreator extends Closeable {

    interface Transformer {
        /**
         * Transforms the given file.
         *
         * @param entryPath the path within the jar file
         * @param input an input stream of the contents of the file
         * @return a new input stream if the file is transformed in some way, the same input stream
         *     if the file is to be kept as is and null if the file should not be packaged.
         */
        @Nullable
        InputStream filter(@NotNull String entryPath, @NotNull InputStream input);
    }

    interface Relocator {
        @NotNull
        String relocate(@NotNull String entryPath);
    }

    void addDirectory(@NotNull Path directory) throws IOException;

    void addDirectory(
            @NotNull Path directory,
            @Nullable Predicate<String> filterOverride,
            @Nullable Transformer transformer,
            @Nullable Relocator relocator)
            throws IOException;

    void addJar(@NotNull Path file) throws IOException;

    void addJar(
            @NotNull Path file,
            @Nullable Predicate<String> filterOverride,
            @Nullable Relocator relocator)
            throws IOException;

    void addFile(@NotNull String entryPath, @NotNull Path file) throws IOException;

    void addEntry(@NotNull String entryPath, @NotNull InputStream input) throws IOException;

    void setCompressionLevel(int level);

    void setManifestProperties(Map<String, String> properties) throws IOException;
}