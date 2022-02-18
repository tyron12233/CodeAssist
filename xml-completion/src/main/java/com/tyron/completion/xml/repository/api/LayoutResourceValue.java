package com.tyron.completion.xml.repository.api;

import androidx.annotation.Nullable;

public interface LayoutResourceValue extends ResourceValue {

    /**
     * Return the root view of this layout, may return null.
     */
    @Nullable
    LayoutInfo getRoot();
}
