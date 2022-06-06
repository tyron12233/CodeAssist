package com.tyron.builder.internal.file;

import com.tyron.builder.internal.watch.registry.impl.Combiners;

import java.io.File;
import java.util.List;

public class DefaultReservedFileSystemLocationRegistry implements ReservedFileSystemLocationRegistry {
    private final FileHierarchySet reservedFileSystemLocations;

    public DefaultReservedFileSystemLocationRegistry(List<ReservedFileSystemLocation> registeredReservedFileSystemLocations) {
        this.reservedFileSystemLocations = registeredReservedFileSystemLocations.stream()
            .map(input -> input.getReservedFileSystemLocation().get().getAsFile())
            .reduce(FileHierarchySet.empty(), FileHierarchySet::plus, Combiners.nonCombining());
    }

    @Override
    public boolean isInReservedFileSystemLocation(File location) {
        return reservedFileSystemLocations.contains(location);
    }
}
