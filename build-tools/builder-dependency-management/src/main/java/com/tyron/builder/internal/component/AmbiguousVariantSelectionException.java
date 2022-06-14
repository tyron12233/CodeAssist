/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.internal.component;

import com.google.common.collect.Ordering;
import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.attributes.AttributeDescriber;
import com.tyron.builder.internal.component.model.AttributeMatcher;
import com.tyron.builder.internal.logging.text.TreeFormatter;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class AmbiguousVariantSelectionException extends VariantSelectionException {

    public AmbiguousVariantSelectionException(AttributeDescriber describer, String producerDisplayName, AttributeContainerInternal requested, List<? extends ResolvedVariant> matches, AttributeMatcher matcher, Set<ResolvedVariant> discarded) {
        super(format(describer, producerDisplayName, requested, matches, matcher, discarded));
    }

    private static String format(AttributeDescriber describer, String producerDisplayName, AttributeContainerInternal consumer, List<? extends ResolvedVariant> variants, AttributeMatcher matcher, Set<ResolvedVariant> discarded) {
        TreeFormatter formatter = new TreeFormatter();
        if (consumer.getAttributes().isEmpty()) {
            formatter.node("More than one variant of " + producerDisplayName + " matches the consumer attributes");
        } else {
            formatter.node("The consumer was configured to find " + describer.describeAttributeSet(consumer.asMap()) + ". However we cannot choose between the following variants of " + producerDisplayName);
        }
        formatter.startChildren();
        for (ResolvedVariant variant : variants) {
            formatter.node(variant.asDescribable().getCapitalizedDisplayName());
            AmbiguousConfigurationSelectionException
                    .formatAttributeMatchesForAmbiguity(formatter, consumer.asImmutable(), matcher, variant.getAttributes().asImmutable(), describer);
        }
        formatter.endChildren();
        if (!discarded.isEmpty()) {
            formatter.node("The following variants were also considered but didn't match the requested attributes:");
            formatter.startChildren();
            discarded.stream()
                .sorted(Comparator.comparing(v -> v.asDescribable().getCapitalizedDisplayName()))
                .forEach(discardedVariant -> {
                    formatter.node(discardedVariant.asDescribable().getCapitalizedDisplayName());
                    AmbiguousConfigurationSelectionException
                            .formatAttributeMatchesForIncompatibility(formatter, consumer.asImmutable(), matcher, discardedVariant.getAttributes().asImmutable(), describer);
                });
            formatter.endChildren();
        }
        return formatter.toString();
    }

    public static void formatAttributes(TreeFormatter formatter, AttributeContainer attributes) {
        formatter.startChildren();
        for (Attribute<?> attribute : Ordering.usingToString().sortedCopy(attributes.keySet())) {
            formatter.node(attribute.getName() + " '" + attributes.getAttribute(attribute) + "'");
        }
        formatter.endChildren();
    }

}
