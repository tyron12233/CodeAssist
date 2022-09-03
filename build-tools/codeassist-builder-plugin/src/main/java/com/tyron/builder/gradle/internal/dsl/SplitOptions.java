package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/** Base data representing how an FULL_APK should be split for a given dimension (density, abi). */
public abstract class SplitOptions implements com.tyron.builder.api.dsl.Split {

    @NonNull private final Set<String> values = Sets.newHashSet();

    @NonNull private final Set<String> allowedValues = Sets.newHashSet();

    protected void init() {
        this.values.addAll(getDefaultValues());
        this.allowedValues.addAll(getAllowedValues());
    }

    protected abstract Set<String> getDefaultValues();
    protected abstract ImmutableSet<String> getAllowedValues();

    @Override
    public void exclude(@NonNull String... excludes) {
        values.removeAll(Arrays.asList(excludes));
    }

    @Override
    public void include(@NonNull String... includes) {
        values.addAll(Arrays.asList(includes));
    }

    @Override
    public void reset() {
        values.clear();
    }

    /**
     * Returns a list of all applicable filters for this dimension.
     *
     * <p>The list can return null, indicating that the no-filter option must also be used.
     *
     * @return the filters to use.
     */
    @NonNull
    public Set<String> getApplicableFilters() {
        if (!isEnable()) {
            return Collections.emptySet();
        }

        Set<String> results = Sets.newHashSetWithExpectedSize(values.size());

        for (String value : values) {
            if (allowedValues.contains(value)) {
                results.add(value);
            }
        }

        return results;
    }
}