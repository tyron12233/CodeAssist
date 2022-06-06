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

import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.tooling.ProjectConnection;
import com.tyron.builder.tooling.internal.consumer.ConnectionParameters;
import com.tyron.builder.tooling.internal.consumer.DefaultProjectConnection;
import com.tyron.builder.tooling.internal.consumer.ProjectConnectionCloseListener;
import com.tyron.builder.tooling.internal.consumer.async.AsyncConsumerActionExecutor;
import com.tyron.builder.tooling.internal.consumer.async.DefaultAsyncConsumerActionExecutor;
import com.tyron.builder.tooling.internal.consumer.connection.CancellableConsumerActionExecutor;
import com.tyron.builder.tooling.internal.consumer.connection.ConsumerActionExecutor;
import com.tyron.builder.tooling.internal.consumer.connection.LazyConsumerActionExecutor;
import com.tyron.builder.tooling.internal.consumer.connection.ProgressLoggingConsumerActionExecutor;
import com.tyron.builder.tooling.internal.consumer.connection.RethrowingErrorsConsumerActionExecutor;
import com.tyron.builder.tooling.internal.consumer.loader.ToolingImplementationLoader;

public class ConnectionFactory {
    private final ToolingImplementationLoader toolingImplementationLoader;
    private final ExecutorFactory executorFactory;
    private final LoggingProvider loggingProvider;

    public ConnectionFactory(ToolingImplementationLoader toolingImplementationLoader, ExecutorFactory executorFactory, LoggingProvider loggingProvider) {
        this.toolingImplementationLoader = toolingImplementationLoader;
        this.executorFactory = executorFactory;
        this.loggingProvider = loggingProvider;
    }

    public ProjectConnection create(Distribution distribution, ConnectionParameters parameters, ProjectConnectionCloseListener listener) {
        ConsumerActionExecutor lazyConnection = new LazyConsumerActionExecutor(distribution, toolingImplementationLoader, loggingProvider, parameters);
        ConsumerActionExecutor cancellableConnection = new CancellableConsumerActionExecutor(lazyConnection);
        ConsumerActionExecutor progressLoggingConnection = new ProgressLoggingConsumerActionExecutor(cancellableConnection, loggingProvider);
        ConsumerActionExecutor rethrowingErrorsConnection = new RethrowingErrorsConsumerActionExecutor(progressLoggingConnection);
        AsyncConsumerActionExecutor asyncConnection = new DefaultAsyncConsumerActionExecutor(rethrowingErrorsConnection, executorFactory);
        return new DefaultProjectConnection(asyncConnection, parameters, listener);
    }

    ToolingImplementationLoader getToolingImplementationLoader() {
        return toolingImplementationLoader;
    }
}
