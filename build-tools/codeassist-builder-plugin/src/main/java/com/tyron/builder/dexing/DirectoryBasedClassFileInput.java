package com.tyron.builder.dexing;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

final class DirectoryBasedClassFileInput implements ClassFileInput {

    @NotNull
    private final Path rootPath;

    public DirectoryBasedClassFileInput(@NotNull Path rootPath) {
        this.rootPath = rootPath;
    }

    @Override
    public void close() throws IOException {
        // nothing to do for folders.
    }

    @Override
    @NotNull
    public Stream<ClassFileEntry> entries(BiPredicate<Path, String> filter) throws IOException {
        return Files.walk(rootPath)
                .filter(p -> CLASS_MATCHER.test(rootPath.relativize(p).toString()))
                .filter(p -> filter.test(rootPath, rootPath.relativize(p).toString()))
                .map(this::createEntryFromPath);
    }

    @Override
    public Path getPath() {
        return rootPath;
    }

    @NotNull
    private ClassFileEntry createEntryFromPath(@NotNull Path path) {
        return new FileBasedClassFileEntry(rootPath, path, this);
    }
}