package com.tyron.xml.completion.repository.api;

import org.jetbrains.annotations.Nullable;

public interface LayoutResourceValue extends ResourceValue {

    /**
     * Return the root view of this layout, may return null.
     */
    @Nullable
    LayoutInfo getRoot();
}
