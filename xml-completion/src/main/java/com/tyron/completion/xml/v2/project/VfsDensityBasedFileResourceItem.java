package com.tyron.completion.xml.v2.project;

import com.android.ide.common.rendering.api.DensityBasedResourceValue;
import com.android.resources.Density;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.HashCodes;
import com.google.common.base.MoreObjects;
import com.tyron.completion.xml.v2.base.RepositoryConfiguration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * A {@link VfsFileResourceItem} for a density based file resource.
 */
public final class VfsDensityBasedFileResourceItem extends VfsFileResourceItem implements DensityBasedResourceValue {
    @NotNull
    private final Density myDensity;

    /**
     * Initializes the resource.
     *
     * @param type          the type of the resource
     * @param name          the name of the resource
     * @param configuration the configuration the resource belongs to
     * @param visibility    the visibility of the resource
     * @param relativePath  defines location of the resource. Exact semantics of the path may vary
     *                      depending on the resource repository
     * @param density       the screen density this resource is associated with
     */
    public VfsDensityBasedFileResourceItem(@NotNull ResourceType type,
                                           @NotNull String name,
                                           @NotNull RepositoryConfiguration configuration,
                                           @NotNull ResourceVisibility visibility,
                                           @NotNull String relativePath,
                                           @NotNull Density density) {
        super(type, name, configuration, visibility, relativePath);
        myDensity = density;
    }

    /**
     * Initializes the resource.
     *
     * @param type          the type of the resource
     * @param name          the name of the resource
     * @param configuration the configuration the resource belongs to
     * @param visibility    the visibility of the resource
     * @param relativePath  defines location of the resource. Exact semantics of the path may vary
     *                      depending on the resource repository
     * @param virtualFile   the virtual file associated with the resource, or null of the resource
     *                      is out of date
     * @param density       the screen density this resource is associated with
     */
    public VfsDensityBasedFileResourceItem(@NotNull ResourceType type,
                                           @NotNull String name,
                                           @NotNull RepositoryConfiguration configuration,
                                           @NotNull ResourceVisibility visibility,
                                           @NotNull String relativePath,
                                           @Nullable File virtualFile,
                                           @NotNull Density density) {
        super(type, name, configuration, visibility, relativePath, virtualFile);
        myDensity = density;
    }

    @Override
    @NotNull
    public Density getResourceDensity() {
        return myDensity;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        VfsDensityBasedFileResourceItem other = (VfsDensityBasedFileResourceItem) obj;
        return myDensity == other.myDensity;
    }

    @Override
    public int hashCode() {
        return HashCodes.mix(super.hashCode(), myDensity.hashCode());
    }

    @Override
    protected int getEncodedDensityForSerialization() {
        return myDensity.ordinal() + 1;
    }

    @Override
    @NotNull
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("namespace", getNamespace())
                .add("type", getResourceType())
                .add("source", getSource())
                .add("density", getResourceDensity())
                .toString();
    }
}