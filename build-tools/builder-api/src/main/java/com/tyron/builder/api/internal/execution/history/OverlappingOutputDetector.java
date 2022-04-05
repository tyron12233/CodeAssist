package com.tyron.builder.api.internal.execution.history;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;

import org.jetbrains.annotations.Nullable;

public interface OverlappingOutputDetector {
    @Nullable
    OverlappingOutputs detect(ImmutableSortedMap<String, FileSystemSnapshot> previous, ImmutableSortedMap<String, FileSystemSnapshot> current);
}