package com.tyron.xml.completion.repository.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface StyleItemResourceValue extends ResourceValue {
    /**
     * Returns contents of the {@code name} XML attribute that defined this style item. This is
     * supposed to be a reference to an {@code attr} resource.
     */
    @NotNull
    String getAttrName();

    /**
     * Returns a {@link ResourceReference} to the {@code attr} resource this item is defined for, if
     * the name was specified using the correct syntax.
     */
    @Nullable
    ResourceReference getAttr();

    /**
     * Returns just the name part of the attribute being referenced, for backwards compatibility
     * with layoutlib. Don't call this method, the item may be in a different namespace than the
     * attribute and the value being referenced, use {@link #getAttr()} instead.
     *
     * @deprecated Use {@link #getAttr()} instead.
     */
    @Deprecated
    @Override
    @NotNull
    String getName();
}
