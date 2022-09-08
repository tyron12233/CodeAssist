package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.AaptOptions;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Objects;

/**
 * Implementation of {@link AaptOptions} that is Serializable.
 *
 * <p>Should only be used for the model.
 */
@Immutable
public final class AaptOptionsImpl implements AaptOptions, Serializable {
    private static final long serialVersionUID = 2L;

    @NonNull private final AaptOptions.Namespacing namespacing;

    public static AaptOptions create(
            @NonNull com.tyron.builder.gradle.internal.dsl.AaptOptions aaptOptions) {
        return new AaptOptionsImpl(
                aaptOptions.getNamespaced() ? Namespacing.REQUIRED : Namespacing.DISABLED);
    }

    public AaptOptionsImpl(@NonNull Namespacing namespacing) {
        this.namespacing = namespacing;
    }

    @NonNull
    @Override
    public Namespacing getNamespacing() {
        return namespacing;
    }

    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("namespacing", namespacing)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AaptOptionsImpl)) {
            return false;
        }
        AaptOptionsImpl that = (AaptOptionsImpl) o;
        return namespacing == that.namespacing;
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespacing);
    }
}
