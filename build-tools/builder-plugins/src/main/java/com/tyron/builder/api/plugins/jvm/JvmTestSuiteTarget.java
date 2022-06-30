/*
 * Copyright 2021 the original author or authors.
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

package com.tyron.builder.api.plugins.jvm;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.api.tasks.testing.Test;
import com.tyron.builder.testing.base.TestSuiteTarget;

/**
 * Defines the target environment against which a {@link JvmTestSuite} will be run.
 *
 * @since 7.3
 */
@Incubating
public interface JvmTestSuiteTarget extends TestSuiteTarget {
    /**
     * The {@link Test} task that runs the tests for the associated test suite.
     *
     * @return provider to the test task
     */
    TaskProvider<Test> getTestTask();
}
