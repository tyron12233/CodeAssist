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
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.logging.progress.ProgressLogger;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.tooling.internal.consumer.ConnectionParameters;
import com.tyron.builder.tooling.internal.consumer.Distribution;
import com.tyron.builder.tooling.internal.consumer.connection.ConsumerConnection;
import com.tyron.builder.tooling.internal.protocol.InternalBuildProgressListener;

import java.io.Closeable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SynchronizedToolingImplementationLoader implements ToolingImplementationLoader, Closeable {
    private final Lock lock = new ReentrantLock();
    private final ToolingImplementationLoader delegate;

    public SynchronizedToolingImplementationLoader(ToolingImplementationLoader delegate) {
        this.delegate = delegate;
    }

    @Override
    public ConsumerConnection create(Distribution distribution, ProgressLoggerFactory progressLoggerFactory, InternalBuildProgressListener progressListener, ConnectionParameters connectionParameters, BuildCancellationToken cancellationToken) {
        if (lock.tryLock()) {
            try {
                return delegate.create(distribution, progressLoggerFactory, progressListener, connectionParameters, cancellationToken);
            } finally {
                lock.unlock();
            }
        }
        ProgressLogger logger = progressLoggerFactory.newOperation(SynchronizedToolingImplementationLoader.class);
        logger.setDescription("Wait for the other thread to finish acquiring the distribution");
        logger.started();
        lock.lock();
        try {
            return delegate.create(distribution, progressLoggerFactory, progressListener, connectionParameters, cancellationToken);
        } finally {
            lock.unlock();
            logger.completed();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            CompositeStoppable.stoppable(delegate).stop();
        } finally {
            lock.unlock();
        }
    }
}
