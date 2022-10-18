package com.tyron.builder.gradle.internal.ide.level2;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.model.level2.GraphItem;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 */
public final class GraphItemImpl implements GraphItem, Serializable {

    @NonNull
    private final String address;
    @NonNull
    private final List<GraphItem> dependencies;
    private final int hashcode;

    public GraphItemImpl(
            @NonNull String address,
            @NonNull List<GraphItem> dependencies) {
        this.address = address;
        this.dependencies = dependencies;
        this.hashcode = computeHashCode();
    }

    @NonNull
    @Override
    public String getArtifactAddress() {
        return address;
    }

    @Nullable
    @Override
    public String getRequestedCoordinates() {
        return null;
    }

    @NonNull
    @Override
    public List<GraphItem> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GraphItemImpl graphItem = (GraphItemImpl) o;
        // quick fail on different hashcode, to avoid manually comparing the children nodes
        return hashcode == graphItem.hashcode
                && Objects.equals(address, graphItem.address)
                && Objects.equals(dependencies, graphItem.dependencies);

    }

    @Override
    public int hashCode() {
        return hashcode;
    }

    private int computeHashCode() {
        return Objects.hash(address, dependencies);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("address", address)
                .add("dependenciesSize", dependencies.size())
                .toString();
    }
}
