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

package com.tyron.builder.tooling.internal.consumer;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.operations.BuildOperationIdFactory;
import com.tyron.builder.internal.operations.DefaultBuildOperationIdFactory;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.tooling.CancellationTokenSource;
import com.tyron.builder.tooling.internal.consumer.DefaultCancellationTokenSource;
import com.tyron.builder.tooling.internal.consumer.DefaultExecutorServiceFactory;
import com.tyron.builder.tooling.internal.consumer.DefaultGradleConnector;
import com.tyron.builder.tooling.internal.consumer.DistributionFactory;
import com.tyron.builder.tooling.internal.consumer.ExecutorServiceFactory;
import com.tyron.builder.tooling.internal.consumer.SynchronizedLogging;
import com.tyron.builder.tooling.internal.consumer.loader.CachingToolingImplementationLoader;
import com.tyron.builder.tooling.internal.consumer.loader.DefaultToolingImplementationLoader;
import com.tyron.builder.tooling.internal.consumer.loader.SynchronizedToolingImplementationLoader;
import com.tyron.builder.tooling.internal.consumer.loader.ToolingImplementationLoader;

public class ConnectorServices {
    private static DefaultServiceRegistry singletonRegistry;

    static {
        singletonRegistry = new ConnectorServiceRegistry();
    }

    public static DefaultGradleConnector createConnector() {
        return singletonRegistry.getFactory(DefaultGradleConnector.class).create();
    }

    public static CancellationTokenSource createCancellationTokenSource() {
        return new DefaultCancellationTokenSource();
    }

    public static void close() {
        singletonRegistry.close();
    }

    /**
     * Resets the state of connector services. Meant to be used only for testing!
     */
    public static void reset() {
        singletonRegistry.close();
        singletonRegistry = new ConnectorServiceRegistry();
    }

    private static class ConnectorServiceRegistry extends DefaultServiceRegistry {
        protected Factory<DefaultGradleConnector> createConnectorFactory(final ConnectionFactory connectionFactory, final DistributionFactory distributionFactory) {
            return new Factory<DefaultGradleConnector>() {
                @Override
                public DefaultGradleConnector create() {
                    return new DefaultGradleConnector(connectionFactory, distributionFactory);
                }
            };
        }

        protected ExecutorFactory createExecutorFactory() {
            return new DefaultExecutorFactory();
        }

        protected ExecutorServiceFactory createExecutorServiceFactory() {
            return new DefaultExecutorServiceFactory();
        }

        protected Clock createTimeProvider() {
            return Time.clock();
        }

        protected DistributionFactory createDistributionFactory(Clock clock) {
            return new DistributionFactory(clock);
        }

        protected ToolingImplementationLoader createToolingImplementationLoader() {
            return new SynchronizedToolingImplementationLoader(new CachingToolingImplementationLoader(new DefaultToolingImplementationLoader()));
        }

        protected BuildOperationIdFactory createBuildOperationIdFactory() {
            return new DefaultBuildOperationIdFactory();
        }

        protected LoggingProvider createLoggingProvider(Clock clock, BuildOperationIdFactory buildOperationIdFactory) {
            return new SynchronizedLogging(clock, buildOperationIdFactory);
        }

        protected ConnectionFactory createConnectionFactory(ToolingImplementationLoader toolingImplementationLoader, ExecutorFactory executorFactory, LoggingProvider loggingProvider) {
            return new ConnectionFactory(toolingImplementationLoader, executorFactory, loggingProvider);
        }
    }
}
