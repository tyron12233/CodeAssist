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

import com.tyron.builder.api.internal.tasks.testing.processors.CaptureTestOutputTestResultProcessor;
import com.tyron.builder.api.internal.tasks.testing.results.AttachParentTestResultProcessor;
import com.tyron.builder.internal.time.Clock;

public class SuiteTestClassProcessor implements TestClassProcessor {
    private final TestClassProcessor processor;
    private final Clock clock;
    private final TestDescriptorInternal suiteDescriptor;
    private TestResultProcessor resultProcessor;

    public SuiteTestClassProcessor(TestDescriptorInternal suiteDescriptor, TestClassProcessor processor,
                                   Clock clock) {
        this.suiteDescriptor = suiteDescriptor;
        this.processor = processor;
        this.clock = clock;
    }

    @Override
    public void startProcessing(TestResultProcessor testResultProcessor) {
        try {
            resultProcessor = new AttachParentTestResultProcessor(new CaptureTestOutputTestResultProcessor(testResultProcessor, new JULRedirector()));
            resultProcessor.started(suiteDescriptor, new TestStartEvent(clock.getCurrentTime()));
            processor.startProcessing(resultProcessor);
        } catch (Throwable t) {
            resultProcessor.failure(suiteDescriptor.getId(), new TestSuiteExecutionException(String.format(
                    "Could not start %s.", suiteDescriptor), t));
        }
    }

    @Override
    public void processTestClass(TestClassRunInfo testClass) {
        try {
            processor.processTestClass(testClass);
        } catch (Throwable t) {
            resultProcessor.failure(suiteDescriptor.getId(), new TestSuiteExecutionException(String.format(
                    "Could not execute test class '%s'.", testClass.getTestClassName()), t));
        }
    }

    @Override
    public void stop() {
        try {
            processor.stop();
        } catch (Throwable t) {
            resultProcessor.failure(suiteDescriptor.getId(), new TestSuiteExecutionException(String.format(
                    "Could not complete execution for %s.", suiteDescriptor), t));
        } finally {
            resultProcessor.completed(suiteDescriptor.getId(), new TestCompleteEvent(clock.getCurrentTime()));
        }
    }

    @Override
    public void stopNow() {
        throw new UnsupportedOperationException("stopNow() should not be invoked on remote worker TestClassProcessor");
    }
}
