/*
 * Copyright 2014 the original author or authors.
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

package com.tyron.builder.tooling.internal.consumer.connection;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.tooling.internal.adapter.ProtocolToModelAdapter;
import com.tyron.builder.tooling.internal.consumer.parameters.BuildCancellationTokenAdapter;
import com.tyron.builder.tooling.internal.consumer.parameters.ConsumerOperationParameters;
import com.tyron.builder.tooling.internal.consumer.versioning.ModelMapping;
import com.tyron.builder.tooling.internal.consumer.versioning.VersionDetails;
import com.tyron.builder.tooling.internal.protocol.BuildResult;
import com.tyron.builder.tooling.internal.protocol.InternalCancellableConnection;
import com.tyron.builder.tooling.internal.protocol.InternalUnsupportedModelException;
import com.tyron.builder.tooling.internal.protocol.ModelIdentifier;
import com.tyron.builder.tooling.model.internal.Exceptions;

public class CancellableModelBuilderBackedModelProducer extends HasCompatibilityMapping implements ModelProducer {
    protected final ProtocolToModelAdapter adapter;
    protected final VersionDetails versionDetails;
    protected final ModelMapping modelMapping;
    private final InternalCancellableConnection builder;
    protected final Transformer<RuntimeException, RuntimeException> exceptionTransformer;

    public CancellableModelBuilderBackedModelProducer(ProtocolToModelAdapter adapter, VersionDetails versionDetails, ModelMapping modelMapping, InternalCancellableConnection builder, Transformer<RuntimeException, RuntimeException> exceptionTransformer) {
        this.adapter = adapter;
        this.versionDetails = versionDetails;
        this.modelMapping = modelMapping;
        this.builder = builder;
        this.exceptionTransformer = exceptionTransformer;
    }

    @Override
    public <T> T produceModel(Class<T> type, ConsumerOperationParameters operationParameters) {
        if (!versionDetails.maySupportModel(type)) {
            throw Exceptions.unsupportedModel(type, versionDetails.getVersion());
        }
        final ModelIdentifier modelIdentifier = modelMapping.getModelIdentifierFromModelType(type);
        BuildResult<?> result;
        try {
            result = builder.getModel(modelIdentifier, new BuildCancellationTokenAdapter(operationParameters.getCancellationToken()), operationParameters);
        } catch (InternalUnsupportedModelException e) {
            throw Exceptions.unknownModel(type, e);
        } catch (RuntimeException e) {
            throw exceptionTransformer.transform(e);
        }
        return applyCompatibilityMapping(adapter.builder(type), operationParameters).build(result.getModel());
    }
}
