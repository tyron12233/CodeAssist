package com.tyron.builder.gradle.internal.dependency;

import com.android.annotations.NonNull;
import com.tyron.builder.api.attributes.BuildTypeAttr;
import com.tyron.builder.api.attributes.ProductFlavorAttr;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.gradle.api.Named;
import org.gradle.api.attributes.AttributeCompatibilityRule;
import org.gradle.api.attributes.CompatibilityCheckDetails;

/** alternate-based Compat rule to handle the different values of attributes. */
public class AlternateCompatibilityRule<T extends Named> implements AttributeCompatibilityRule<T> {

    @NonNull private final Map<String, List<String>> alternates;

    protected AlternateCompatibilityRule(@NonNull Map<String, List<String>> alternates) {
        this.alternates = alternates;
    }

    @Override
    public void execute(CompatibilityCheckDetails<T> details) {
        final T producerValue = details.getProducerValue();
        final T consumerValue = details.getConsumerValue();
        if (producerValue.equals(consumerValue)) {
            details.compatible();
        } else {
            List<String> alternatesForValue = alternates.get(consumerValue.getName());
            if (alternatesForValue != null
                    && alternatesForValue.contains(producerValue.getName())) {
                details.compatible();
            }
        }
    }

    public static class BuildTypeRule extends AlternateCompatibilityRule<BuildTypeAttr> {

        @Inject
        public BuildTypeRule(@NonNull Map<String, List<String>> alternates) {
            super(alternates);
        }
    }

    public static class ProductFlavorRule extends AlternateCompatibilityRule<ProductFlavorAttr> {

        @Inject
        public ProductFlavorRule(@NonNull Map<String, List<String>> alternates) {
            super(alternates);
        }
    }
}
