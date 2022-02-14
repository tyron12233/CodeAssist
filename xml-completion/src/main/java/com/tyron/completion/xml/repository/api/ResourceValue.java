package com.tyron.completion.xml.repository.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.io.Serializable;

/** Represents an Android resource with a name and a string value. */
public interface ResourceValue extends Serializable {

    default boolean isPublic() {
        return true;
    }

    @NonNull
    ResourceType getResourceType();

    @NonNull
    ResourceNamespace getNamespace();

    @NonNull
    String getName();

    /**
     * Returns the name of the library where this resource was found or null if it is not from a
     * library.
     */
    @Nullable
    String getLibraryName();

    /** Returns true if the resource is user defined. */
    boolean isUserDefined();

    boolean isFramework();

    /**
     * Returns the value of the resource, as defined in the XML. This can be null, for example for
     * instances of {@link StyleResourceValue}.
     */
    @Nullable
    String getValue();

    @NonNull
    ResourceReference asReference();

    @NonNull
    default ResourceUrl getResourceUrl() {
        return asReference().getResourceUrl();
    }

    /**
     * If this {@link ResourceValue} references another one, returns a {@link ResourceReference} to
     * it, otherwise null.
     *
     * <p>This method should be called before inspecting the textual value ({@link #getValue}), as
     * it handles namespaces correctly.
     */
    @Nullable
    default ResourceReference getReference() {
        String value = getValue();
        if (value == null) {
            return null;
        }

        ResourceUrl url = ResourceUrl.parse(value);
        if (url == null) {
            return null;
        }

        return url.resolve(getNamespace(), getNamespaceResolver());
    }

    /**
     * Similar to {@link #getValue}, but returns the raw XML value. This is <b>usually</b> the same
     * as {@link #getValue}, but with a few exceptions. For example, for markup strings, you can
     * have {@code <string name="markup">This is <b>bold</b></string>}. Here, {@link #getValue} will
     * return "{@code This is bold}" -- e.g. just the plain text flattened. However, this method
     * will return "{@code This is <b>bold</b>}", which preserves the XML markup elements.
     */
    @Nullable
    default String getRawXmlValue() {
        return getValue();
    }

    /**
     * Sets the value of the resource.
     *
     * @param value the new value
     */
    void setValue(@Nullable String value);

    /**
     * Returns the namespace resolver that can be used to resolve any name prefixes in the string
     * values associated with this resource.
     */
    @NonNull
    ResourceNamespace.Resolver getNamespaceResolver();
}

