package com.tyron.builder.gradle.internal.dependency;

import com.google.common.collect.Maps;
import com.tyron.builder.api.attributes.BuildTypeAttr;
import com.tyron.builder.api.attributes.ProductFlavorAttr;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.Named;
import org.gradle.api.attributes.AttributeDisambiguationRule;
import org.gradle.api.attributes.MultipleCandidatesDetails;
import org.jetbrains.annotations.NotNull;

/** alternate-based Disambiguation rule to handle the different values of attributes. */
public class AlternateDisambiguationRule<T extends Named>
        implements AttributeDisambiguationRule<T> {

    /** Sorted alternates from high to low priority, associated to a requested value. */
    @NotNull
    private final Map<String, List<String>> alternates;

    protected AlternateDisambiguationRule(@NotNull Map<String, List<String>> alternates) {
        this.alternates = alternates;
    }

    @Override
    public void execute(MultipleCandidatesDetails<T> details) {
        final T consumerValue = details.getConsumerValue();
        if (consumerValue == null) {
            return;
        }

        List<String> alternatesForValue = alternates.get(consumerValue.getName());
        if (alternatesForValue == null) {
            return;
        }

        Set<T> candidates = details.getCandidateValues();

        if (candidates.contains(consumerValue)) {
            details.closestMatch(consumerValue);

        } else if (alternatesForValue.size() == 1) {
            String fallback = alternatesForValue.get(0);
            // quick optim for single alternate
            for (T candidate : candidates) {
                if (candidate.getName().equals(fallback)) {
                    details.closestMatch(candidate);
                    return;
                }
            }

        } else {
            // build a map to go from name->T
            Map<String, T> map = Maps.newHashMapWithExpectedSize(candidates.size());
            for (T candidate : candidates) {
                map.put(candidate.getName(), candidate);
            }

            // then go through the alternates and pick the first one
            for (String fallback : alternatesForValue) {
                T candidate = map.get(fallback);
                if (candidate != null) {
                    details.closestMatch(candidate);
                    return;
                }
            }
        }
    }

    public static class BuildTypeRule extends AlternateDisambiguationRule<BuildTypeAttr> {

        @Inject
        public BuildTypeRule(@NotNull Map<String, List<String>> alternates) {
            super(alternates);
        }
    }

    public static class ProductFlavorRule extends AlternateDisambiguationRule<ProductFlavorAttr> {

        @Inject
        public ProductFlavorRule(@NotNull Map<String, List<String>> alternates) {
            super(alternates);
        }
    }
}
