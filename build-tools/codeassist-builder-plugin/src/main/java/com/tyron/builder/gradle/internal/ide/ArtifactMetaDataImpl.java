package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.ArtifactMetaData;
import java.io.Serializable;
import java.util.Objects;

/**
 * Implementation of ArtifactMetaData that is serializable
 */
@Immutable
public final class ArtifactMetaDataImpl implements ArtifactMetaData, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final String name;
    private final boolean isTest;
    private final int type;

    public ArtifactMetaDataImpl(@NonNull String name, boolean isTest, int type) {
        this.name = name;
        this.isTest = isTest;
        this.type = type;
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isTest() {
        return isTest;
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ArtifactMetaDataImpl that = (ArtifactMetaDataImpl) o;
        return isTest == that.isTest &&
                type == that.type &&
                Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, isTest, type);
    }
}
