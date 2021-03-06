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

package com.tyron.builder.api.internal.tasks.testing.results;

import com.tyron.builder.api.internal.tasks.testing.DecoratingTestDescriptor;
import com.tyron.builder.api.internal.tasks.testing.TestCompleteEvent;
import com.tyron.builder.api.internal.tasks.testing.TestDescriptorInternal;
import com.tyron.builder.api.internal.tasks.testing.TestResultProcessor;
import com.tyron.builder.api.internal.tasks.testing.TestStartEvent;
import com.tyron.builder.api.tasks.testing.TestOutputEvent;
import com.tyron.builder.api.tasks.testing.TestResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StateTrackingTestResultProcessor implements TestResultProcessor {
    private final Map<Object, TestState> executing = new HashMap<Object, TestState>();
    private TestDescriptorInternal currentParent;
    private final TestListenerInternal delegate;

    public StateTrackingTestResultProcessor(TestListenerInternal delegate) {
        this.delegate = delegate;
    }

    @Override
    public final void started(TestDescriptorInternal test, TestStartEvent event) {
        TestDescriptorInternal parent = null;
        if (event.getParentId() != null) {
            parent = executing.get(event.getParentId()).test;
        }
        TestState state = new TestState(new DecoratingTestDescriptor(test, parent), event, executing);
        TestState oldState = executing.put(test.getId(), state);
        if (oldState != null) {
            throw new IllegalArgumentException(String.format("Received a start event for %s with duplicate id '%s'.",
                    test, test.getId()));
        }

        delegate.started(state.test, event);
    }

    private void ensureChildrenCompleted(Object testId, long endTime) {
        List<Object> incompleteChildren = new ArrayList<Object>();
        for (Map.Entry<Object, TestState> entry : executing.entrySet()) {
            if (testId.equals(entry.getValue().startEvent.getParentId())) {
                incompleteChildren.add(entry.getKey());
            }
        }

        if (!incompleteChildren.isEmpty()) {
            TestCompleteEvent skippedEvent = new TestCompleteEvent(endTime, TestResult.ResultType.SKIPPED);
            for (Object childTestId : incompleteChildren) {
                completed(childTestId, skippedEvent);
            }
        }
    }

    @Override
    public final void completed(Object testId, TestCompleteEvent event) {
        ensureChildrenCompleted(testId, event.getEndTime());

        TestState testState = executing.remove(testId);
        if (testState == null) {
            throw new IllegalArgumentException(String.format(
                    "Received a completed event for test with unknown id '%s'. Registered test ids: '%s'",
                    testId, executing.keySet()));
        }

        //In case the output event arrives after completion of the test
        //and we need to have a matching descriptor to inform the user which test this output belongs to
        //we will use the current parent

        //(SF) This approach should generally work because at the moment we reset capturing output per suite
        //(see CaptureTestOutputTestResultProcessor) and that reset happens earlier in the chain.
        //So in theory when suite is completed, the output redirector has been already stopped
        //and there shouldn't be any output events passed
        //See also GRADLE-2035
        currentParent = testState.test.getParent();

        testState.completed(event);
        delegate.completed(testState.test, new DefaultTestResult(testState), event);
    }

    @Override
    public final void failure(Object testId, Throwable result) {
        TestState testState = executing.get(testId);
        if (testState == null) {
            throw new IllegalArgumentException(String.format(
                    "Received a failure event for test with unknown id '%s'. Registered test ids: '%s'",
                    testId, executing.keySet()));
        }
        testState.failures.add(result);
    }

    @Override
    public final void output(Object testId, TestOutputEvent event) {
        delegate.output(findDescriptor(testId), event);
    }

    private TestDescriptorInternal findDescriptor(Object testId) {
        TestState state = executing.get(testId);
        if (state != null) {
            return state.test;
        }

        if (currentParent != null) {
            return currentParent;
        }

        //in theory this should not happen
        return new UnknownTestDescriptor();
    }
}
