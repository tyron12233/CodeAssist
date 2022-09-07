package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.FilterData;
import com.tyron.builder.VariantOutput;
import com.tyron.builder.gradle.internal.scope.GradleAwareFilterData;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Objects;

/** Implementation of {@link com.tyron.builder.FilterData} interface */
@Immutable
public final class FilterDataImpl implements GradleAwareFilterData, Serializable {
    private static final long serialVersionUID = 1L;

    private final String filterType;
    private final String identifier;

    public FilterDataImpl(VariantOutput.FilterType filterType, String identifier) {
        this(filterType.name(), identifier);
    }

    public FilterDataImpl(String filterType, String identifier) {
        this.filterType = filterType;
        this.identifier = identifier;
    }

    @NonNull
    @Override
    public String getIdentifier() {
        return identifier;
    }

    @NonNull
    @Override
    public String getFilterType() {
        return filterType;
    }

    public static GradleAwareFilterData build(final String filterType, final String identifier) {
        return new FilterDataImpl(filterType, identifier);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FilterDataImpl that = (FilterDataImpl) o;
        return Objects.equals(filterType, that.filterType) &&
                Objects.equals(identifier, that.identifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filterType, identifier);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(FilterData.class)
                .add("type", filterType)
                .add("value", identifier)
                .toString();
    }
}
