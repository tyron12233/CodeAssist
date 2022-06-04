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

import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.logging.progress.ProgressLogger;
import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
import com.tyron.builder.internal.logging.events.ProgressEvent;
import com.tyron.builder.internal.logging.progress.ProgressListener;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;
import com.tyron.builder.tooling.internal.consumer.LoggingProvider;
import com.tyron.builder.tooling.internal.consumer.parameters.ConsumerOperationParameters;
import com.tyron.builder.tooling.internal.protocol.ProgressListenerVersion1;

/**
 * Provides some high-level progress information.
 */
public class ProgressLoggingConsumerActionExecutor implements ConsumerActionExecutor {
    private final ConsumerActionExecutor actionExecutor;
    private final LoggingProvider loggingProvider;

    public ProgressLoggingConsumerActionExecutor(ConsumerActionExecutor actionExecutor, LoggingProvider loggingProvider) {
        this.actionExecutor = actionExecutor;
        this.loggingProvider = loggingProvider;
    }

    @Override
    public void stop() {
        actionExecutor.stop();
    }

    @Override
    public String getDisplayName() {
        return actionExecutor.getDisplayName();
    }

    @Override
    public <T> T run(ConsumerAction<T> action) throws UnsupportedOperationException, IllegalStateException {
        ConsumerOperationParameters parameters = action.getParameters();
        ProgressListenerAdapter listener = new ProgressListenerAdapter(parameters.getProgressListener());
        ListenerManager listenerManager = loggingProvider.getListenerManager();
        listenerManager.addListener(listener);
        try {
            ProgressLogger progressLogger = loggingProvider.getProgressLoggerFactory().newOperation(ProgressLoggingConsumerActionExecutor.class);
            progressLogger.setDescription("Build");
            progressLogger.started();
            try {
                return actionExecutor.run(action);
            } finally {
                progressLogger.completed();
            }
        } finally {
            listenerManager.removeListener(listener);
        }
    }

    @Override
    public void disconnect() {
        actionExecutor.disconnect();
    }

    private static class ProgressListenerAdapter implements ProgressListener {
        private final ProgressListenerVersion1 progressListener;

        public ProgressListenerAdapter(ProgressListenerVersion1 progressListener) {
            this.progressListener = progressListener;
        }

        @Override
        public void started(ProgressStartEvent event) {
            progressListener.onOperationStart(event.getDescription());
        }

        @Override
        public void progress(ProgressEvent event) {
        }

        @Override
        public void completed(ProgressCompleteEvent event) {
            progressListener.onOperationEnd();
        }
    }
}
