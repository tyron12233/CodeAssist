package com.tyron.builder.dexing.r8;

import com.android.tools.r8.ArchiveClassFileProvider;
import com.android.tools.r8.ProgramResource;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

class CachingArchiveClassFileProvider extends ArchiveClassFileProvider {

    @NotNull
    private ConcurrentHashMap<String, ProgramResource> resources = new ConcurrentHashMap<>();

    public CachingArchiveClassFileProvider(@NotNull Path archive) throws IOException {
        super(archive);
    }

    @NotNull
    @Override
    public ProgramResource getProgramResource(@NotNull String descriptor) {
        return resources.computeIfAbsent(descriptor, super::getProgramResource);
    }
}
