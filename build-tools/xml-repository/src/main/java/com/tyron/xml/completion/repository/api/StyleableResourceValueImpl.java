package com.tyron.xml.completion.repository.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.util.ArrayList;
import java.util.List;

/** A resource value representing a declare-styleable resource. */
public class StyleableResourceValueImpl extends ResourceValueImpl
        implements StyleableResourceValue {
    @NotNull private final List<AttrResourceValue> attrs = new ArrayList<>();

    public StyleableResourceValueImpl(
            @NotNull ResourceNamespace namespace,
            @NotNull String name,
            @Nullable String value,
            @Nullable String libraryName) {
        super(namespace, ResourceType.STYLEABLE, name, value, libraryName);
    }

    public StyleableResourceValueImpl(
            @NotNull ResourceReference reference,
            @Nullable String value,
            @Nullable String libraryName) {
        super(reference, value, libraryName);
        assert reference.getResourceType() == ResourceType.STYLEABLE;
    }

    @Override
    @NotNull
    public List<AttrResourceValue> getAllAttributes() {
        return attrs;
    }

    public void addValue(@NotNull AttrResourceValue attr) {
        assert attr.isFramework() || !isFramework()
                : "Can't add non-framework attributes to framework resource.";
        attrs.add(attr);
    }
}
