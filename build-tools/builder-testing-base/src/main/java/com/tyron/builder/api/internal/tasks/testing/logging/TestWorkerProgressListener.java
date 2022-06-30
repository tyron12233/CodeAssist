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

package com.tyron.builder.api.internal.tasks.testing.logging;

import com.tyron.builder.api.internal.tasks.testing.DecoratingTestDescriptor;
import com.tyron.builder.api.internal.tasks.testing.DefaultTestClassDescriptor;
import com.tyron.builder.api.internal.tasks.testing.TestCompleteEvent;
import com.tyron.builder.api.internal.tasks.testing.TestDescriptorInternal;
import com.tyron.builder.api.internal.tasks.testing.TestStartEvent;
import com.tyron.builder.api.internal.tasks.testing.results.TestListenerInternal;
import com.tyron.builder.api.tasks.testing.TestOutputEvent;
import com.tyron.builder.api.tasks.testing.TestResult;
import com.tyron.builder.internal.logging.progress.ProgressLogger;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class TestWorkerProgressListener implements TestListenerInternal {

    private static final int MAX_TEST_NAME_LENGTH = 60;

    private final ProgressLoggerFactory factory;
    private final ProgressLogger parentProgressLogger;
    private final Map<String, ProgressLogger> testWorkerProgressLoggers = new HashMap<String, ProgressLogger>();

    public TestWorkerProgressListener(ProgressLoggerFactory factory, ProgressLogger parentProgressLogger) {
        this.factory = factory;
        this.parentProgressLogger = parentProgressLogger;
    }

    @Override
    public void started(TestDescriptorInternal testDescriptor, TestStartEvent startEvent) {
        boolean testClassDescriptor = isDefaultTestClassDescriptor(testDescriptor);

        if (testClassDescriptor) {
            String description = createProgressLoggerDescription(testDescriptor);

            if (!testWorkerProgressLoggers.containsKey(description)) {
                ProgressLogger progressLogger = factory.newOperation(TestWorkerProgressListener.class, parentProgressLogger);
                progressLogger.start(description, description);
                testWorkerProgressLoggers.put(description, progressLogger);
            }
        }
    }

    @Override
    public void completed(TestDescriptorInternal testDescriptor, TestResult testResult, TestCompleteEvent completeEvent) {
        boolean testClassDescriptor = isDefaultTestClassDescriptor(testDescriptor);

        if (testClassDescriptor) {
            String description = createProgressLoggerDescription(testDescriptor);

            if (testWorkerProgressLoggers.containsKey(description)) {
                ProgressLogger progressLogger = testWorkerProgressLoggers.remove(description);
                progressLogger.completed();
            }
        }
    }

    @Override
    public void output(TestDescriptorInternal testDescriptor, TestOutputEvent event) {
    }

    /**
     * Completes and clears registered progress loggers even if test worker crashed.
     */
    public void completeAll() {
        for (ProgressLogger progressLogger : testWorkerProgressLoggers.values()) {
            progressLogger.completed();
        }

        testWorkerProgressLoggers.clear();
    }

    private boolean isDefaultTestClassDescriptor(TestDescriptorInternal testDescriptor) {
        if (testDescriptor.isComposite()
            && testDescriptor instanceof DecoratingTestDescriptor
            && ((DecoratingTestDescriptor)testDescriptor).getDescriptor() instanceof DefaultTestClassDescriptor) {
            return true;
        }

        return false;
    }

    private String createProgressLoggerDescription(TestDescriptorInternal testDescriptor) {
        DecoratingTestDescriptor decoratingTestDescriptor = (DecoratingTestDescriptor)testDescriptor;
        DefaultTestClassDescriptor defaultTestClassDescriptor = (DefaultTestClassDescriptor)decoratingTestDescriptor.getDescriptor();
        return "Executing test " + JavaClassNameFormatter.abbreviateJavaPackage(defaultTestClassDescriptor.getClassName(), MAX_TEST_NAME_LENGTH);
    }

    Map<String, ProgressLogger> getTestWorkerProgressLoggers() {
        return testWorkerProgressLoggers;
    }
}
