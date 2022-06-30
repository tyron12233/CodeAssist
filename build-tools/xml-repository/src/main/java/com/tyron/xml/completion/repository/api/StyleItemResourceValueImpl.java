package com.tyron.xml.completion.repository.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.tyron.builder.compiler.manifest.resources.ResourceType;

/**
 * A straightforward implementation of the {@link StyleItemResourceValue} interface.
 */
public class StyleItemResourceValueImpl extends ResourceValueImpl implements StyleItemResourceValue {
    @NotNull private final String attributeName;

    public StyleItemResourceValueImpl(
            @NotNull ResourceNamespace namespace,
            @NotNull String attributeName,
            @Nullable String value,
            @Nullable String libraryName) {
        super(namespace, ResourceType.STYLE_ITEM, "<item>", value, libraryName);
        this.attributeName = attributeName;
    }

    /**
     * Returns contents of the {@code name} XML attribute that defined this style item. This is
     * supposed to be a reference to an {@code attr} resource.
     */
    @Override
    @NotNull
    public String getAttrName() {
        return attributeName;
    }

    /**
     * Returns a {@link ResourceReference} to the {@code attr} resource this item is defined for, if
     * the name was specified using the correct syntax.
     */
    @Override
    @Nullable
    public ResourceReference getAttr() {
        ResourceUrl url = ResourceUrl.parseAttrReference(attributeName);
        if (url == null) {
            return null;
        }

        return url.resolve(getNamespace(), mNamespaceResolver);
    }

    /**
     * Returns just the name part of the attribute being referenced, for backwards compatibility
     * with layoutlib. Don't call this method, the item may be in a different namespace than the
     * attribute and the value being referenced, use {@link #getAttr()} instead.
     *
     * @deprecated TODO(namespaces): Throw in this method, once layoutlib correctly calls {@link
     *     #getAttr()} instead.
     */
    @Deprecated
    @Override
    @NotNull
    public String getName() {
        ResourceUrl url = ResourceUrl.parseAttrReference(attributeName);
        if (url != null) {
            return url.name;
        } else {
            return attributeName;
        }
    }

    @Override
    @NotNull
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("namespace", getNamespace())
                .add("attribute", attributeName)
                .add("value", getValue())
                .toString();
    }
}
