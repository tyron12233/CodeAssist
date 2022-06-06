/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.api.internal.tasks.testing.operations;

import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.operations.BuildOperationIdFactory;
import com.tyron.builder.internal.operations.BuildOperationListenerManager;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.time.Clock;

public class TestExecutionBuildOperationBuildSessionScopeServices {

    TestListenerBuildOperationAdapter createTestListenerBuildOperationAdapter(BuildOperationListenerManager listener, BuildOperationIdFactory buildOperationIdFactory, Clock clock) {
        return new TestListenerBuildOperationAdapter(listener.getBroadcaster(), buildOperationIdFactory, clock);
    }

    void configure(ServiceRegistration serviceRegistration, ListenerManager listenerManager, TestListenerBuildOperationAdapter testListenerBuildOperationAdapter) {
        listenerManager.addListener(testListenerBuildOperationAdapter);
    }

}
