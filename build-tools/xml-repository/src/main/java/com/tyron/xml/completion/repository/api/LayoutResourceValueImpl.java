package com.tyron.xml.completion.repository.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LayoutResourceValueImpl extends ResourceValueImpl implements LayoutResourceValue {

    private final LayoutInfo mRoot;

    public LayoutResourceValueImpl(@NotNull ResourceReference reference,
                                   @Nullable String value,
                                   @Nullable String libraryName,
                                   @Nullable LayoutInfo root) {
        super(reference, value, libraryName);

        mRoot = root;
    }


    @Nullable
    @Override
    public LayoutInfo getRoot() {
        return mRoot;
    }
}
