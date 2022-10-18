package com.tyron.builder.gradle.internal.ide.level2;

import com.android.annotations.NonNull;
import com.tyron.builder.model.level2.GlobalLibraryMap;
import com.tyron.builder.model.level2.Library;
import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import java.util.Map;

public class GlobalLibraryMapImpl implements GlobalLibraryMap, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final Map<String, Library> map;

    public GlobalLibraryMapImpl(
            @NonNull Map<String, Library> map) {
        this.map = ImmutableMap.copyOf(map);
    }

    @NonNull
    @Override
    public Map<String, Library> getLibraries() {
        return map;
    }
}
