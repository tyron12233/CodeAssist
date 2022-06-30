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

package com.tyron.builder.api.internal.tasks.testing.results;

import com.tyron.builder.api.internal.tasks.testing.TestCompleteEvent;
import com.tyron.builder.api.internal.tasks.testing.TestDescriptorInternal;
import com.tyron.builder.api.internal.tasks.testing.TestStartEvent;
import com.tyron.builder.api.tasks.testing.TestOutputEvent;
import com.tyron.builder.api.tasks.testing.TestResult;
import com.tyron.builder.internal.scan.UsedByScanPlugin;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

@UsedByScanPlugin
@EventScope(Scopes.Build.class)
public interface TestListenerInternal {
    void started(TestDescriptorInternal testDescriptor, TestStartEvent startEvent);

    void completed(TestDescriptorInternal testDescriptor, TestResult testResult, TestCompleteEvent completeEvent);

    void output(TestDescriptorInternal testDescriptor, TestOutputEvent event);
}
