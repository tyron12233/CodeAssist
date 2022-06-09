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

package com.tyron.builder.tooling.internal.consumer;

import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.initialization.DefaultBuildCancellationToken;
import com.tyron.builder.tooling.CancellationToken;
import com.tyron.builder.tooling.CancellationTokenSource;

public final class DefaultCancellationTokenSource implements CancellationTokenSource {
    private final CancellationTokenImpl tokenImpl;

    public DefaultCancellationTokenSource() {
        tokenImpl = new CancellationTokenImpl(new DefaultBuildCancellationToken());
    }

    @Override
    public void cancel() {
        tokenImpl.token.cancel();
    }

    @Override
    public CancellationToken token() {
        return tokenImpl;
    }

    private static class CancellationTokenImpl implements CancellationToken, CancellationTokenInternal {
        private final DefaultBuildCancellationToken token;

        private CancellationTokenImpl(DefaultBuildCancellationToken token) {
            this.token = token;
        }

        @Override
        public BuildCancellationToken getToken() {
            return token;
        }

        @Override
        public boolean isCancellationRequested() {
            return token.isCancellationRequested();
        }
    }
}
