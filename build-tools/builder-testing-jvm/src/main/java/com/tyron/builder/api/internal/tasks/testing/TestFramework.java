/*
 * Copyright 2010 the original author or authors.
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

package com.tyron.builder.api.internal.tasks.testing;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.tasks.testing.detection.TestFrameworkDetector;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Nested;
import com.tyron.builder.api.tasks.testing.TestFrameworkOptions;
import com.tyron.builder.internal.scan.UsedByScanPlugin;
import com.tyron.builder.process.internal.worker.WorkerProcessBuilder;

import java.io.Closeable;
import java.util.List;

@UsedByScanPlugin("test-retry")
public interface TestFramework extends Closeable {

    /**
     * Returns a detector which is used to determine which of the candidate class files correspond to test classes to be
     * executed.
     */
    @Internal
    TestFrameworkDetector getDetector();

    @Nested
    TestFrameworkOptions getOptions();

    /**
     * Returns a factory which is used to create a {@link com.tyron.builder.api.internal.tasks.testing.TestClassProcessor} in
     * each worker process. This factory is serialized across to the worker process, and then its {@link
     * com.tyron.builder.api.internal.tasks.testing.WorkerTestClassProcessorFactory#create(com.tyron.builder.internal.service.ServiceRegistry)}
     * method is called to create the test processor.
     */
    @Internal
    WorkerTestClassProcessorFactory getProcessorFactory();

    /**
     * Returns an action which is used to perform some framework specific worker process configuration. This action is
     * executed before starting each worker process.
     */
    @Internal
    Action<WorkerProcessBuilder> getWorkerConfigurationAction();

    /**
     * Returns a list of modules the test worker requires on the --module-path if it runs as a module.
     */
    @Internal
    List<String> getTestWorkerImplementationModules();
}
