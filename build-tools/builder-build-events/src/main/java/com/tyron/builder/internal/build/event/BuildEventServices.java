/*
 * Copyright 2019 the original author or authors.
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

package com.tyron.builder.internal.build.event;

import com.tyron.builder.internal.operations.BuildOperationAncestryTracker;
import com.tyron.builder.internal.operations.BuildOperationListenerManager;
import com.tyron.builder.internal.operations.DefaultBuildOperationAncestryTracker;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;

public class BuildEventServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.add(DefaultBuildEventsListenerRegistry.class);
        registration.addProvider(new Object() {
            BuildOperationAncestryTracker createBuildOperationAncestryTracker(BuildOperationListenerManager listenerManager) {
                DefaultBuildOperationAncestryTracker tracker = new DefaultBuildOperationAncestryTracker();
                listenerManager.addListener(tracker);
                return tracker;
            }
        });
    }
}
