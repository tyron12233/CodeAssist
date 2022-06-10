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

package com.tyron.builder.api.tasks.testing;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

/**
 * Listens to the output events like printing to standard output or error
 */
@EventScope(Scopes.Build.class)
public interface TestOutputListener {

    /**
     * Fired when during test execution anything is printed to standard output or error
     *
     * @param testDescriptor describes the test
     * @param outputEvent the event that contains the output message and the destination (standard output or error, etc.)
     */
    void onOutput(TestDescriptor testDescriptor, TestOutputEvent outputEvent);
}