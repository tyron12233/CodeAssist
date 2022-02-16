package com.tyron.builder.compiler.manifest.configuration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.Density;
import com.tyron.builder.compiler.manifest.resources.ResourceEnum;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource Qualifier for Screen Pixel Density.
 */
public final class DensityQualifier extends EnumBasedResourceQualifier {

    public static final String NAME = "Density";

    /**
     * The qualifier to be used for configurables when there is no qualifier present. This should
     * not be used for the reference configuration.
     */
    private static final DensityQualifier NULL_QUALIFIER = new DensityQualifier(true);

    /**
     * null iff <code>this == {@link #NULL_QUALIFIER}</code>
     */
    @Nullable
    private final Density mValue;

    private static final Pattern sDensityLegacyPattern = Pattern.compile("^(\\d+)dpi$");

    public DensityQualifier() {
        this(Density.MEDIUM);
    }

    public DensityQualifier(@NotNull Density value) {
        // value is marked as NonNull so that no usages from outside this method use a null value.
        mValue = value;
    }

    private DensityQualifier(@SuppressWarnings("UnusedParameters") boolean ignored) {
        mValue = null;
    }

    // Not marking as NonNull or Nullable because it can technically return null (for
    // NULL_QUALIFIER) but usually won't. So, no need to keep checking for null.
    @SuppressWarnings("NullableProblems")
    public Density getValue() {
        return mValue;
    }

    @Override
    public ResourceEnum getEnumValue() {
        return mValue;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getShortName() {
        return NAME;
    }

    @Override
    public int since() {
        return 4;
    }

    @Override
    public boolean checkAndSet(String value, FolderConfiguration config) {
        Density density = Density.getEnum(value);
        if (density == null) {

            // attempt to read a legacy value.
            Matcher m = sDensityLegacyPattern.matcher(value);
            if (m.matches()) {
                String v = m.group(1);

                try {
                    density = Density.getEnum(Integer.parseInt(v));
                } catch (NumberFormatException e) {
                    // looks like the string we extracted wasn't a valid number
                    // which really shouldn't happen since the regexp would have failed.
                    throw new AssertionError(e);
                }
            }
        }

        if (density != null) {
            config.setDensityQualifier(new DensityQualifier(density));
            return true;
        }

        return false;
    }

    @Override
    public boolean isValid() {
        return this != NULL_QUALIFIER;
    }

    @Override
    public DensityQualifier getNullQualifier() {
        return NULL_QUALIFIER;
    }

    @Override
    public boolean isMatchFor(ResourceQualifier qualifier) {
        // as long as there's a density qualifier, it's always a match.
        // The best match will be found later.
        return qualifier instanceof DensityQualifier;
    }

    @Override
    public boolean isBetterMatchThan(@Nullable ResourceQualifier compareTo,
                                     @NotNull ResourceQualifier reference) {
        if (compareTo == null) {
            return true;
        }

        Density other = ((DensityQualifier) compareTo).mValue;
        Density required = ((DensityQualifier) reference).mValue;
        assert required != null
                : "NULL_QUALIFIER Density Qualifier shouldn't be part of the reference";
        Density value = mValue;
        if (value == other) {
            return false;
        }

        value = value == null ? Density.MEDIUM : value;
        other = other == null ? Density.MEDIUM : other;

        // Always prefer ANYDPI
        if (value == Density.ANYDPI) {
            return true;
        }
        if (other == Density.ANYDPI) {
            return false;
        }
        if (required == Density.ANYDPI || required == Density.NODPI) {
            // Not sure when this would happen, but that's what is there in
            // ResourceTypes.cpp in the method ResTable_config::isBetterThan
            required = Density.MEDIUM;
        }
        int requiredDensity = required.getDpiValue();

        // DENSITY_ANY is now dealt with. We should look to pick a density bucket and potentially
        // scale it. Any density is potentially useful because the system will scale it.  Scaling
        // down is generally better than scaling up.
        int high = value.getDpiValue();
        int low = other.getDpiValue();
        boolean bImBigger = true;
        if (low > high) {
            int temp = high;
            high = low;
            low = temp;
            bImBigger = false;
        } else if (low == high && low == Density.MEDIUM.getDpiValue()) {
            // This if branch is not present in the platform's code. However, it's necessary to
            // remove uncertainty in which configuration is chosen in case of no qualifier vs mdpi

            // mdpi is preferred to no qualifier on devices with resolution >= Medium. For ldpi,
            // no qualifier is preferred to mdpi.
            return requiredDensity >= Density.MEDIUM.getDpiValue() ^ this == NULL_QUALIFIER;
        }
        if (requiredDensity > high) {
            // reference higher than both, return the higher.
            return bImBigger;
        }
        if (low >= requiredDensity) {
            // reference lower than both, return lower;
            return !bImBigger;
        }
        // saying that scaling down is 2x better than up
        if (((2 * low) - requiredDensity) * high > requiredDensity * requiredDensity) {
            return !bImBigger;
        } else {
            return bImBigger;
        }
    }
}
