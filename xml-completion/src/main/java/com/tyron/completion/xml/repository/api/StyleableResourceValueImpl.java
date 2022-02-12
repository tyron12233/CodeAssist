package com.tyron.completion.xml.repository.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.util.ArrayList;
import java.util.List;

/** A resource value representing a declare-styleable resource. */
public class StyleableResourceValueImpl extends ResourceValueImpl
        implements StyleableResourceValue {
    @NonNull private final List<AttrResourceValue> attrs = new ArrayList<>();

    public StyleableResourceValueImpl(
            @NonNull ResourceNamespace namespace,
            @NonNull String name,
            @Nullable String value,
            @Nullable String libraryName) {
        super(namespace, ResourceType.STYLEABLE, name, value, libraryName);
    }

    public StyleableResourceValueImpl(
            @NonNull ResourceReference reference,
            @Nullable String value,
            @Nullable String libraryName) {
        super(reference, value, libraryName);
        assert reference.getResourceType() == ResourceType.STYLEABLE;
    }

    @Override
    @NonNull
    public List<AttrResourceValue> getAllAttributes() {
        return attrs;
    }

    public void addValue(@NonNull AttrResourceValue attr) {
        assert attr.isFramework() || !isFramework()
                : "Can't add non-framework attributes to framework resource.";
        attrs.add(attr);
    }
}
