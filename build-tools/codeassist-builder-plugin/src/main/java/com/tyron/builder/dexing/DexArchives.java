package com.tyron.builder.dexing;

import com.google.common.collect.Lists;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Helper methods for the {@link DexArchive}. */
public final class DexArchives {

    private DexArchives() {
    }

    /**
     * Creates a {@link com.android.builder.dexing.DexArchive} from the specified path. It supports
     * .jar files and directories as inputs.
     *
     * <p>In case of a .jar file, note there are two mutually exclusive modes, write-only and
     * read-only. In case of a write-only mode, only allowed operation is adding entries. If
     * read-only mode is used, entires can only be read.
     */
    @NotNull
    public static DexArchive fromInput(@NotNull Path path) throws IOException {
        if (ClassFileInputs.jarMatcher.matches(path)) {
            return new NonIncrementalJarDexArchive(path);
        } else {
            return new DirDexArchive(path);
        }
    }

    @NotNull
    static List<DexArchiveEntry> getEntriesFromSingleArchive(@NotNull Path archivePath)
            throws IOException {
        try (DexArchive archive = fromInput(archivePath)) {
            return archive.getSortedDexArchiveEntries();
        }
    }

    @NotNull
    static List<DexArchiveEntry> getAllEntriesFromArchives(@NotNull Collection<Path> inputs)
            throws IOException {
        List<DexArchiveEntry> entries = Lists.newArrayList();
        for (Path p : inputs) {
            entries.addAll(getEntriesFromSingleArchive(p));
        }
        return entries;
    }
}