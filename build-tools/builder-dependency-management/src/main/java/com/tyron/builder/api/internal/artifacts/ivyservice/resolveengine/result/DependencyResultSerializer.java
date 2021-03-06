/*
 * Copyright 2013 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result;

import com.tyron.builder.internal.resolve.ModuleVersionResolveException;

import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.result.ComponentSelectionReason;
import com.tyron.builder.api.artifacts.result.ResolvedVariantResult;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphEdge;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.ResolvedGraphDependency;

import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;

import java.io.IOException;
import java.util.Map;

public class DependencyResultSerializer {
    private final static byte SUCCESSFUL = 0;
    private final static byte FAILED = 1;
    private final ComponentSelectionReasonSerializer componentSelectionReasonSerializer;
    private final ResolvedVariantResultSerializer resolvedVariantResultSerializer;

    public DependencyResultSerializer(ResolvedVariantResultSerializer resolvedVariantResultSerializer, ComponentSelectionDescriptorFactory componentSelectionDescriptorFactory) {
        this.componentSelectionReasonSerializer = new ComponentSelectionReasonSerializer(componentSelectionDescriptorFactory);
        this.resolvedVariantResultSerializer = resolvedVariantResultSerializer;
    }

    public ResolvedGraphDependency read(Decoder decoder, Map<Long, ComponentSelector> selectors, Map<ComponentSelector, ModuleVersionResolveException> failures) throws IOException {
        Long selectorId = decoder.readSmallLong();
        ComponentSelector requested = selectors.get(selectorId);
        boolean constraint = decoder.readBoolean();
        ResolvedVariantResult fromVariant = resolvedVariantResultSerializer.read(decoder);
        byte resultByte = decoder.readByte();
        if (resultByte == SUCCESSFUL) {
            Long selectedId = decoder.readSmallLong();

            return new DetachedResolvedGraphDependency(requested, selectedId, null, null, constraint, fromVariant, resolvedVariantResultSerializer.read(decoder));
        } else if (resultByte == FAILED) {
            ComponentSelectionReason reason = componentSelectionReasonSerializer.read(decoder);
            ModuleVersionResolveException failure = failures.get(requested);
            return new DetachedResolvedGraphDependency(requested, null, reason, failure, constraint, fromVariant, null);
        } else {
            throw new IllegalArgumentException("Unknown result type: " + resultByte);
        }
    }

    public void write(Encoder encoder, DependencyGraphEdge value) throws IOException {
        encoder.writeSmallLong(value.getSelector().getResultId());
        encoder.writeBoolean(value.isConstraint());
        resolvedVariantResultSerializer.write(encoder, value.getFromVariant());
        if (value.getFailure() == null) {
            encoder.writeByte(SUCCESSFUL);
            encoder.writeSmallLong(value.getSelected());
            resolvedVariantResultSerializer.write(encoder, value.getSelectedVariant());
        } else {
            encoder.writeByte(FAILED);
            componentSelectionReasonSerializer.write(encoder, value.getReason());
        }
    }
}
