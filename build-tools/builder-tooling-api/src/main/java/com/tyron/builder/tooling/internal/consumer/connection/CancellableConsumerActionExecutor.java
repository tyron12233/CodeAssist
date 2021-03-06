/*
 * Copyright 2016 the original author or authors.
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

import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.tooling.BuildCancelledException;

public class CancellableConsumerActionExecutor implements ConsumerActionExecutor {
    private final ConsumerActionExecutor delegate;

    public CancellableConsumerActionExecutor(ConsumerActionExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public String getDisplayName() {
        return delegate.getDisplayName();
    }

    @Override
    public <T> T run(ConsumerAction<T> action) throws UnsupportedOperationException, IllegalStateException {
        BuildCancellationToken cancellationToken = action.getParameters().getCancellationToken();
        if (cancellationToken.isCancellationRequested()) {
            throw new BuildCancelledException("Build cancelled");
        }
        return delegate.run(action);
    }

    @Override
    public void disconnect() {
        delegate.disconnect();
    }
}
