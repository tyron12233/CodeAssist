package org.gradle.internal.execution.history;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.snapshot.FileSystemSnapshot;

import org.jetbrains.annotations.Nullable;

public interface OverlappingOutputDetector {
    @Nullable
    OverlappingOutputs detect(ImmutableSortedMap<String, FileSystemSnapshot> previous, ImmutableSortedMap<String, FileSystemSnapshot> current);
}