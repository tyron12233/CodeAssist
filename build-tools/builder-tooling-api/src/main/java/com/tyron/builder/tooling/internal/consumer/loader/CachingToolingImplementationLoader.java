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
package com.tyron.builder.tooling.internal.consumer.loader;

import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.tooling.internal.consumer.ConnectionParameters;
import com.tyron.builder.tooling.internal.consumer.Distribution;
import com.tyron.builder.tooling.internal.consumer.connection.ConsumerConnection;
import com.tyron.builder.tooling.internal.protocol.InternalBuildProgressListener;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;

public class CachingToolingImplementationLoader implements ToolingImplementationLoader, Closeable {
    private final ToolingImplementationLoader loader;
    private final Map<ClassPath, ConsumerConnection> connections = new HashMap<ClassPath, ConsumerConnection>();

    public CachingToolingImplementationLoader(ToolingImplementationLoader loader) {
        this.loader = loader;
    }

    @Override
    public ConsumerConnection create(Distribution distribution, ProgressLoggerFactory progressLoggerFactory, InternalBuildProgressListener progressListener, ConnectionParameters connectionParameters, BuildCancellationToken cancellationToken) {
        ClassPath classpath = distribution.getToolingImplementationClasspath(progressLoggerFactory, progressListener, connectionParameters, cancellationToken);

        ConsumerConnection connection = connections.get(classpath);
        if (connection == null) {
            connection = loader.create(distribution, progressLoggerFactory, progressListener, connectionParameters, cancellationToken);
            connections.put(classpath, connection);
        }

        return connection;
    }

    @Override
    public void close() {
        try {
            CompositeStoppable.stoppable(connections.values()).stop();
        } finally {
            connections.clear();
        }
    }
}
