/*
 * Copyright 2011 the original author or authors.
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

import com.tyron.builder.tooling.BuildAction;
import com.tyron.builder.tooling.UnsupportedVersionException;
import com.tyron.builder.tooling.internal.adapter.ProtocolToModelAdapter;
import com.tyron.builder.tooling.internal.build.VersionOnlyBuildEnvironment;
import com.tyron.builder.tooling.internal.consumer.PhasedBuildAction;
import com.tyron.builder.tooling.internal.consumer.TestExecutionRequest;
import com.tyron.builder.tooling.internal.consumer.parameters.ConsumerOperationParameters;
import com.tyron.builder.tooling.internal.protocol.ConnectionMetaDataVersion1;
import com.tyron.builder.tooling.internal.protocol.ConnectionVersion4;
import com.tyron.builder.tooling.model.build.BuildEnvironment;

import java.util.List;

/**
 * An adapter for unsupported connection using a {@code ConnectionVersion4} based provider.
 *
 * <p>Used for providers &gt;= 1.0-milestone-3 and &lt;= 2.5.</p>
 */
public class UnsupportedOlderVersionConnection implements ConsumerConnection {
    private final ProtocolToModelAdapter adapter;
    private final String version;
    private final ConnectionMetaDataVersion1 metaData;

    public UnsupportedOlderVersionConnection(ConnectionVersion4 delegate, ProtocolToModelAdapter adapter) {
        this.adapter = adapter;
        this.version = delegate.getMetaData().getVersion();
        this.metaData = delegate.getMetaData();
    }

    @Override
    public void stop() {
    }

    @Override
    public String getDisplayName() {
        return metaData.getDisplayName();
    }

    @Override
    public <T> T run(Class<T> type, ConsumerOperationParameters operationParameters) throws UnsupportedOperationException, IllegalStateException {
        if (type.equals(BuildEnvironment.class)) {
            return adapter.adapt(type, doGetBuildEnvironment());
        }
        throw unsupported();
    }

    private Object doGetBuildEnvironment() {
        return new VersionOnlyBuildEnvironment(version);
    }

    @Override
    public <T> T run(BuildAction<T> action, ConsumerOperationParameters operationParameters) throws UnsupportedOperationException, IllegalStateException {
        throw unsupported();
    }

    @Override
    public void run(PhasedBuildAction phasedBuildAction, ConsumerOperationParameters operationParameters) {
        throw unsupported();
    }

    @Override
    public void runTests(TestExecutionRequest testExecutionRequest, ConsumerOperationParameters operationParameters) {
        throw unsupported();
    }

    @Override
    public void notifyDaemonsAboutChangedPaths(List<String> changedPaths, ConsumerOperationParameters operationParameters) {
        throw unsupported();
    }

    @Override
    public void stopWhenIdle(ConsumerOperationParameters operationParameters) {
        throw unsupported();
    }

    private UnsupportedVersionException unsupported() {
        return new UnsupportedVersionException(String.format("Support for builds using Gradle versions older than 2.6 was removed in tooling API version 5.0. You are currently using Gradle version %s. You should upgrade your Gradle build to use Gradle 2.6 or later.", version));
    }

}
