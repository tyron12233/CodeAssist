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

package com.tyron.builder.api.internal.artifacts.transform;

import com.google.common.collect.Lists;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant;
import com.tyron.builder.internal.component.AmbiguousVariantSelectionException;
import com.tyron.builder.internal.component.VariantSelectionException;

import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.logging.text.TreeFormatter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AmbiguousTransformException extends VariantSelectionException {
    public AmbiguousTransformException(String producerDisplayName, AttributeContainerInternal requested, List<Pair<ResolvedVariant, ConsumerVariantMatchResult.ConsumerVariant>> candidates) {
        super(format(producerDisplayName, requested, candidates));
    }

    private static String format(String producerDisplayName, AttributeContainerInternal requested, List<Pair<ResolvedVariant, ConsumerVariantMatchResult.ConsumerVariant>> candidates) {
        TreeFormatter formatter = new TreeFormatter();
        formatter.node("Found multiple transforms that can produce a variant of " + producerDisplayName + " with requested attributes");
        AmbiguousVariantSelectionException.formatAttributes(formatter, requested);
        formatter.node("Found the following transforms");
        Map<ResolvedVariant, List<ConsumerVariantMatchResult.ConsumerVariant>> variantToTransforms = candidates.stream()
            .collect(Collectors.toMap(Pair::getLeft,
                    candidate -> Lists.newArrayList(candidate.getRight()),
                    (List<ConsumerVariantMatchResult.ConsumerVariant> orig, List<ConsumerVariantMatchResult.ConsumerVariant> add) -> {
                        orig.addAll(add);
                        return orig;
                    },
                    LinkedHashMap::new));
        formatter.startChildren();
        for (Map.Entry<ResolvedVariant, List<ConsumerVariantMatchResult.ConsumerVariant>> entry : variantToTransforms.entrySet()) {
            formatter.node("From '" + entry.getKey().asDescribable().getDisplayName() + "'");
            formatter.startChildren();
            formatter.node("With source attributes");
            AmbiguousVariantSelectionException.formatAttributes(formatter, entry.getKey().getAttributes());
            formatter.node("Candidate transform(s)");
            formatter.startChildren();
            for (ConsumerVariantMatchResult.ConsumerVariant transform : entry.getValue()) {
                formatter.node("Transform '" + transform.transformation.getDisplayName() + "' producing attributes:");
                AmbiguousVariantSelectionException.formatAttributes(formatter, transform.attributes);
            }
            formatter.endChildren();
            formatter.endChildren();
        }
        formatter.endChildren();
        return formatter.toString();
    }
}
