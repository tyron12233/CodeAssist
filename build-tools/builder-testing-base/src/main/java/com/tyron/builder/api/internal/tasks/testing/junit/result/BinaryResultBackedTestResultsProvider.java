/*
 * Copyright 2012 the original author or authors.
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

package com.tyron.builder.api.internal.tasks.testing.junit.result;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.tasks.testing.TestOutputEvent;

import java.io.File;
import java.io.Writer;

public class BinaryResultBackedTestResultsProvider extends TestOutputStoreBackedResultsProvider {
    private final TestResultSerializer resultSerializer;

    public BinaryResultBackedTestResultsProvider(File resultsDir) {
        super(new TestOutputStore(resultsDir));
        this.resultSerializer = new TestResultSerializer(resultsDir);
    }

    @Override
    public boolean hasOutput(final long classId, final TestOutputEvent.Destination destination) {
        final boolean[] hasOutput = new boolean[1];
        withReader(new Action<TestOutputStore.Reader>() {
            @Override
            public void execute(TestOutputStore.Reader reader) {
                hasOutput[0] = reader.hasOutput(classId, destination);
            }
        });
        return hasOutput[0];
    }

    @Override
    public boolean hasOutput(long classId, long testId, TestOutputEvent.Destination destination) {
        return false;
    }

    @Override
    public void writeAllOutput(final long classId, final TestOutputEvent.Destination destination, final Writer writer) {
        withReader(new Action<TestOutputStore.Reader>() {
            @Override
            public void execute(TestOutputStore.Reader reader) {
                reader.writeAllOutput(classId, destination, writer);
            }
        });
    }

    @Override
    public boolean isHasResults() {
        return resultSerializer.isHasResults();
    }

    @Override
    public void writeNonTestOutput(final long classId, final TestOutputEvent.Destination destination, final Writer writer) {
        withReader(new Action<TestOutputStore.Reader>() {
            @Override
            public void execute(TestOutputStore.Reader reader) {
                reader.writeNonTestOutput(classId, destination, writer);
            }
        });
    }

    @Override
    public void writeTestOutput(final long classId, final long testId, final TestOutputEvent.Destination destination, final Writer writer) {
        withReader(new Action<TestOutputStore.Reader>() {
            @Override
            public void execute(TestOutputStore.Reader reader) {
                reader.writeTestOutput(classId, testId, destination, writer);
            }
        });
    }

    @Override
    public void visitClasses(final Action<? super TestClassResult> visitor) {
        resultSerializer.read(visitor);
    }
}
