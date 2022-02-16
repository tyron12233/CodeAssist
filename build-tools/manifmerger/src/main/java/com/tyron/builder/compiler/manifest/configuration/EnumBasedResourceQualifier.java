package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceEnum;

/** Base class for {@link ResourceQualifier} whose value is an {@link ResourceEnum}. */
public abstract class EnumBasedResourceQualifier extends ResourceQualifier {

    @Nullable
    public abstract ResourceEnum getEnumValue();

    @Override
    public boolean isValid() {
        return getEnumValue() != null;
    }

    @Override
    public boolean hasFakeValue() {
        ResourceEnum value = getEnumValue();
        return value != null && value.isFakeValue();

    }

    @Override
    public boolean equals(@Nullable Object qualifier) {
        return qualifier != null
                && qualifier.getClass() == getClass()
                && getEnumValue() == ((EnumBasedResourceQualifier) qualifier).getEnumValue();
    }

    @Override
    public int hashCode() {
        ResourceEnum value = getEnumValue();
        if (value != null) {
            return value.hashCode();
        }

        return 0;
    }

    /**
     * Returns the string used to represent this qualifier in the folder name.
     */
    @Override
    public final String getFolderSegment() {
        ResourceEnum value = getEnumValue();
        if (value != null) {
            return value.getResourceValue();
        }

        return ""; //$NON-NLS-1$
    }


    @Override
    public String getShortDisplayValue() {
        ResourceEnum value = getEnumValue();
        if (value != null) {
            return value.getShortDisplayValue();
        }

        return ""; //$NON-NLS-1$
    }

    @Override
    public String getLongDisplayValue() {
        ResourceEnum value = getEnumValue();
        if (value != null) {
            return value.getLongDisplayValue();
        }

        return ""; //$NON-NLS-1$
    }

}
