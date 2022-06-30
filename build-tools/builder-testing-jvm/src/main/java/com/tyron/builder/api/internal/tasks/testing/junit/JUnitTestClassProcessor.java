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

package com.tyron.builder.api.internal.tasks.testing.junit;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.tasks.testing.TestResultProcessor;
import com.tyron.builder.api.internal.tasks.testing.results.AttachParentTestResultProcessor;
import com.tyron.builder.internal.actor.Actor;
import com.tyron.builder.internal.actor.ActorFactory;
import com.tyron.builder.internal.id.IdGenerator;
import com.tyron.builder.internal.time.Clock;

public class JUnitTestClassProcessor extends AbstractJUnitTestClassProcessor<JUnitSpec> {

    public JUnitTestClassProcessor(JUnitSpec spec, IdGenerator<?> idGenerator, ActorFactory actorFactory, Clock clock) {
        super(spec, idGenerator, actorFactory, clock);
    }

    @Override
    protected TestResultProcessor createResultProcessorChain(TestResultProcessor resultProcessor) {
        TestResultProcessor resultProcessorChain = new AttachParentTestResultProcessor(resultProcessor);
        return new TestClassExecutionEventGenerator(resultProcessorChain, idGenerator, clock);
    }

    @Override
    protected Action<String> createTestExecutor(Actor resultProcessorActor) {
        TestResultProcessor threadSafeResultProcessor = resultProcessorActor.getProxy(TestResultProcessor.class);
        TestClassExecutionListener threadSafeTestClassListener = resultProcessorActor.getProxy(TestClassExecutionListener.class);

        JUnitTestEventAdapter junitEventAdapter = new JUnitTestEventAdapter(threadSafeResultProcessor, clock, idGenerator);
        return new JUnitTestClassExecutor(Thread.currentThread().getContextClassLoader(), spec, junitEventAdapter, threadSafeTestClassListener);
    }

}
