/*
 * Copyright 2018 the original author or authors.
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

import com.tyron.builder.tooling.BuildActionFailureException;
import com.tyron.builder.tooling.IntermediateResultHandler;
import com.tyron.builder.tooling.internal.adapter.ProtocolToModelAdapter;
import com.tyron.builder.tooling.internal.consumer.DefaultPhasedActionResultListener;
import com.tyron.builder.tooling.internal.consumer.PhasedBuildAction;
import com.tyron.builder.tooling.internal.consumer.connection.ParameterAcceptingConsumerConnection;
import com.tyron.builder.tooling.internal.consumer.parameters.BuildCancellationTokenAdapter;
import com.tyron.builder.tooling.internal.consumer.parameters.ConsumerOperationParameters;
import com.tyron.builder.tooling.internal.consumer.versioning.ModelMapping;
import com.tyron.builder.tooling.internal.consumer.versioning.VersionDetails;
import com.tyron.builder.tooling.internal.protocol.ConnectionVersion4;
import com.tyron.builder.tooling.internal.protocol.InternalBuildActionFailureException;
import com.tyron.builder.tooling.internal.protocol.InternalBuildActionVersion2;
import com.tyron.builder.tooling.internal.protocol.InternalPhasedAction;
import com.tyron.builder.tooling.internal.protocol.InternalPhasedActionConnection;
import com.tyron.builder.tooling.internal.protocol.PhasedActionResultListener;

import javax.annotation.Nullable;
import java.io.File;

/**
 * An adapter for {@link InternalPhasedActionConnection}.
 *
 * <p>Used for providers &gt;= 4.8.</p>
 */
public class PhasedActionAwareConsumerConnection extends ParameterAcceptingConsumerConnection {

    public PhasedActionAwareConsumerConnection(ConnectionVersion4 delegate, ModelMapping modelMapping, ProtocolToModelAdapter adapter) {
        super(delegate, modelMapping, adapter);
    }

    @Override
    public void run(PhasedBuildAction phasedBuildAction, ConsumerOperationParameters operationParameters) {
        InternalPhasedActionConnection connection = (InternalPhasedActionConnection) getDelegate();
        PhasedActionResultListener listener = new DefaultPhasedActionResultListener(getHandler(phasedBuildAction.getProjectsLoadedAction()),
            getHandler(phasedBuildAction.getBuildFinishedAction()));
        InternalPhasedAction internalPhasedAction = getPhasedAction(phasedBuildAction, operationParameters.getProjectDir(), getVersionDetails());
        try {
            connection.run(internalPhasedAction, listener, new BuildCancellationTokenAdapter(operationParameters.getCancellationToken()), operationParameters);
        } catch (InternalBuildActionFailureException e) {
            throw new BuildActionFailureException("The supplied phased action failed with an exception.", e.getCause());
        }
    }

    @Nullable
    private static <T> IntermediateResultHandler<? super T> getHandler(@Nullable PhasedBuildAction.BuildActionWrapper<T> wrapper) {
        return wrapper == null ? null : wrapper.getHandler();
    }

    private static InternalPhasedAction getPhasedAction(PhasedBuildAction phasedBuildAction, File rootDir, VersionDetails versionDetails) {
        return new InternalPhasedActionAdapter(getAction(phasedBuildAction.getProjectsLoadedAction(), rootDir, versionDetails),
            getAction(phasedBuildAction.getBuildFinishedAction(), rootDir, versionDetails));
    }

    @Nullable
    private static <T> InternalBuildActionVersion2<T> getAction(@Nullable PhasedBuildAction.BuildActionWrapper<T> wrapper, File rootDir, VersionDetails versionDetails) {
        return wrapper == null ? null : new InternalBuildActionAdapter<T>(wrapper.getAction(), rootDir, versionDetails);
    }
}
