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

import com.tyron.builder.internal.event.DefaultListenerManager;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.logging.progress.DefaultProgressLoggerFactory;
import com.tyron.builder.internal.logging.progress.ProgressListener;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.operations.BuildOperationIdFactory;
import com.tyron.builder.internal.service.scopes.Scope.Global;
import com.tyron.builder.internal.time.Clock;

/**
 * Provides logging services per thread.
 */
public class SynchronizedLogging implements LoggingProvider {
    private final ThreadLocal<ThreadLoggingServices> services = new ThreadLocal<ThreadLoggingServices>();
    private final Clock clock;
    private final BuildOperationIdFactory buildOperationIdFactory;

    public SynchronizedLogging(Clock clock, BuildOperationIdFactory buildOperationIdFactory) {
        this.clock = clock;
        this.buildOperationIdFactory = buildOperationIdFactory;
    }

    @Override
    public ListenerManager getListenerManager() {
        return services().listenerManager;
    }

    @Override
    public ProgressLoggerFactory getProgressLoggerFactory() {
        return services().progressLoggerFactory;
    }

    private ThreadLoggingServices services() {
        ThreadLoggingServices threadServices = services.get();
        if (threadServices == null) {
            DefaultListenerManager manager = new DefaultListenerManager(Global.class);
            DefaultProgressLoggerFactory progressLoggerFactory = new DefaultProgressLoggerFactory(manager.getBroadcaster(ProgressListener.class), clock, buildOperationIdFactory);
            threadServices = new ThreadLoggingServices(manager, progressLoggerFactory);
            services.set(threadServices);
        }
        return threadServices;
    }

    private static class ThreadLoggingServices {
        final ListenerManager listenerManager;
        final ProgressLoggerFactory progressLoggerFactory;

        private ThreadLoggingServices(ListenerManager listenerManager, ProgressLoggerFactory progressLoggerFactory) {
            this.listenerManager = listenerManager;
            this.progressLoggerFactory = progressLoggerFactory;
        }
    }
}
