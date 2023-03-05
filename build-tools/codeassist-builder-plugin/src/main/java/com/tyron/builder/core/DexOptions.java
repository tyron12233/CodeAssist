package com.tyron.builder.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface DexOptions {
    boolean getPreDexLibraries();

    boolean getJumboMode();

    boolean getDexInProcess();

    boolean getKeepRuntimeAnnotatedClasses();

    @Nullable
    String getJavaMaxHeapSize();

    @Nullable
    Integer getThreadCount();

    @Nullable
    Integer getMaxProcessCount();

    @NotNull
    List<String> getAdditionalParameters();
}