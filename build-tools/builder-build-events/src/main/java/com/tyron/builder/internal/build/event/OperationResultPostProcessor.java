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

import com.tyron.builder.internal.build.event.types.AbstractTaskResult;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.OperationFinishEvent;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.operations.OperationStartEvent;

/**
 * Post-processor for {@link AbstractTaskResult} instances.
 *
 * <p>May be used to add information to results by returning specialized subclasses,
 * e.g. from internal language-specific plugins like Java.
 */
public interface OperationResultPostProcessor {
    void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent);

    void finished(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent);

    AbstractTaskResult process(AbstractTaskResult taskResult, OperationIdentifier taskBuildOperationId);
}
