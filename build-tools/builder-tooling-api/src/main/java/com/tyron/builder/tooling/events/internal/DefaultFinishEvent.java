/*
 * Copyright 2015 the original author or authors.
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

package com.tyron.builder.tooling.events.internal;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.tooling.events.FinishEvent;
import com.tyron.builder.tooling.events.OperationDescriptor;
import com.tyron.builder.tooling.events.OperationResult;

/**
 * Base implementation of the {@code FinishEvent} interface.
 */
public class DefaultFinishEvent<D extends OperationDescriptor, R extends OperationResult> extends BaseProgressEvent implements FinishEvent {

    private final R result;

    public DefaultFinishEvent(long eventTime, String displayName, D descriptor, R result) {
        super(eventTime, displayName, descriptor);
        this.result = result;
    }

    @Override
    public D getDescriptor() {
        return Cast.uncheckedCast(super.getDescriptor());
    }

    @Override
    public R getResult() {
        return result;
    }

}
