package com.tyron.builder.api.model.internal.type;

import com.google.common.collect.ImmutableList;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

class WildcardTypeWrapper implements WildcardWrapper {
    private final TypeWrapper[] upperBounds;
    private final TypeWrapper[] lowerBounds;
    private final int hashCode;

    public WildcardTypeWrapper(TypeWrapper[] upperBounds, TypeWrapper[] lowerBounds, int hashCode) {
        this.upperBounds = upperBounds;
        this.lowerBounds = lowerBounds;
        this.hashCode = hashCode;
    }

    @Override
    public Class<?> getRawClass() {
        if (upperBounds.length > 0) {
            return upperBounds[0].getRawClass();
        }
        return Object.class;
    }

    @Override
    public boolean isAssignableFrom(TypeWrapper wrapper) {
        return ParameterizedTypeWrapper.contains(this, wrapper);
    }

    @Override
    public TypeWrapper getUpperBound() {
        return upperBounds[0];
    }

    @Nullable
    @Override
    public TypeWrapper getLowerBound() {
        return lowerBounds.length > 0 ? lowerBounds[0] : null;
    }

    @Override
    public void collectClasses(ImmutableList.Builder<Class<?>> builder) {
        for (TypeWrapper upperBound : upperBounds) {
            upperBound.collectClasses(builder);
        }
        for (TypeWrapper lowerBound : lowerBounds) {
            lowerBound.collectClasses(builder);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WildcardTypeWrapper)) {
            return false;
        } else {
            WildcardTypeWrapper var2 = (WildcardTypeWrapper) o;
            return Arrays.equals(this.lowerBounds, var2.lowerBounds) && Arrays.equals(this.upperBounds, var2.upperBounds);
        }
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return getRepresentation(true);
    }

    @Override
    public String getRepresentation(boolean full) {

        TypeWrapper[] bounds = lowerBounds;
        StringBuilder sb = new StringBuilder();

        if (lowerBounds.length > 0) {
            sb.append("? super ");
        } else {
            if (upperBounds.length > 0 && !upperBounds[0].getRawClass().equals(Object.class)) {
                bounds = this.upperBounds;
                sb.append("? extends ");
            } else {
                return "?";
            }
        }

        assert bounds.length > 0;

        boolean first = true;
        for (TypeWrapper bound : bounds) {
            if (!first) {
                sb.append(" & ");
            }

            first = false;
            sb.append(bound.getRepresentation(full));
        }

        return sb.toString();
    }
}