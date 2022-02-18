package com.tyron.completion.xml.repository.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class LayoutResourceValueImpl extends ResourceValueImpl implements LayoutResourceValue {

    private final LayoutInfo mRoot;

    public LayoutResourceValueImpl(@NonNull ResourceReference reference,
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
