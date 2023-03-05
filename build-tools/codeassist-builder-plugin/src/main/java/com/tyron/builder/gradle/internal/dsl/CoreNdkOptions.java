package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import java.util.List;
import java.util.Set;

/** @deprecated See {@link com.tyron.builder.api.dsl.Ndk} */
@Deprecated
public interface CoreNdkOptions {

    @Nullable
    String getModuleName();

    @Nullable
    String getcFlags();

    @Nullable
    List<String> getLdLibs();

    @NonNull
    Set<String> getAbiFilters();

    @Nullable
    String getStl();

    @Nullable
    Integer getJobs();

    @Nullable
    String getDebugSymbolLevel();
}