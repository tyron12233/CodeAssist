package com.tyron.builder.internal.aapt;

import com.android.annotations.NonNull;
import com.android.resources.Density;
import com.google.common.collect.Iterables;

import java.util.function.Predicate;

/**
 * Utilities used in the {@code aapt} package.
 */
public final class AaptUtils {

    /**
     * Predicate that evaluates whether a resource config is a density as per
     * {@link Density#getEnum(String)}.
     */
    private static final Predicate<String> IS_DENSITY = d -> Density.getEnum(d) != null;

    /**
     * Utility class: no constructor.
     */
    private AaptUtils() {
        /*
         * Never executed.
         */
    }

    /**
     * Obtains resource configs that are densities.
     *
     * @param configs the resource configs
     * @return resource configs that are recognized as densities as per
     * {@link Density#getEnum(String)}
     */
    public static Iterable<String> getDensityResConfigs(@NonNull Iterable<String> configs) {
        return Iterables.filter(configs, IS_DENSITY::test);
    }

    /**
     * Obtains resource configs that are not densities.
     *
     * @return resource configs that are not recognized as densities as per
     * {@link Density#getEnum(String)}
     */
    public static Iterable<String> getNonDensityResConfigs(@NonNull Iterable<String> configs) {
        return Iterables.filter(configs, IS_DENSITY.negate()::test);
    }
}