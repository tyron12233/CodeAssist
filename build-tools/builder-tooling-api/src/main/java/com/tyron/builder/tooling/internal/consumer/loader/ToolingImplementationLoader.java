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
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.tooling.internal.consumer.ConnectionParameters;
import com.tyron.builder.tooling.internal.consumer.Distribution;
import com.tyron.builder.tooling.internal.consumer.connection.ConsumerConnection;
import com.tyron.builder.tooling.internal.protocol.InternalBuildProgressListener;

/**
 * Loads the Tooling API implementation classes from the given Gradle distribution and adapts them to the current Tooling API interfaces.
 */
public interface ToolingImplementationLoader {
    ConsumerConnection create(Distribution distribution, ProgressLoggerFactory progressLoggerFactory, InternalBuildProgressListener progressListener, ConnectionParameters connectionParameters, BuildCancellationToken cancellationToken);
}
