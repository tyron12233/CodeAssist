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

package com.tyron.builder.api.internal.tasks.testing.testng;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.internal.tasks.testing.TestClassProcessor;
import com.tyron.builder.api.internal.tasks.testing.TestClassRunInfo;
import com.tyron.builder.api.internal.tasks.testing.TestResultProcessor;
import com.tyron.builder.api.internal.tasks.testing.filter.TestSelectionMatcher;
import com.tyron.builder.internal.actor.Actor;
import com.tyron.builder.internal.actor.ActorFactory;
import com.tyron.builder.internal.id.IdGenerator;
import com.tyron.builder.internal.reflect.JavaMethod;
import com.tyron.builder.internal.reflect.JavaReflectionUtil;
import com.tyron.builder.internal.reflect.NoSuchMethodException;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.util.internal.CollectionUtils;
import com.tyron.builder.util.internal.GFileUtils;
import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ISuite;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.TestNG;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static com.tyron.builder.api.tasks.testing.testng.TestNGOptions.DEFAULT_CONFIG_FAILURE_POLICY;

public class TestNGTestClassProcessor implements TestClassProcessor {
    private final List<Class<?>> testClasses = new ArrayList<Class<?>>();
    private final File testReportDir;
    private final TestNGSpec options;
    private final List<File> suiteFiles;
    private final IdGenerator<?> idGenerator;
    private final Clock clock;
    private final ActorFactory actorFactory;
    private ClassLoader applicationClassLoader;
    private Actor resultProcessorActor;
    private TestResultProcessor resultProcessor;

    public TestNGTestClassProcessor(File testReportDir, TestNGSpec options, List<File> suiteFiles, IdGenerator<?> idGenerator, Clock clock, ActorFactory actorFactory) {
        this.testReportDir = testReportDir;
        this.options = options;
        this.suiteFiles = suiteFiles;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.actorFactory = actorFactory;
    }

    @Override
    public void startProcessing(TestResultProcessor resultProcessor) {
        // Wrap the processor in an actor, to make it thread-safe
        resultProcessorActor = actorFactory.createBlockingActor(resultProcessor);
        this.resultProcessor = resultProcessorActor.getProxy(TestResultProcessor.class);
        applicationClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @Override
    public void processTestClass(TestClassRunInfo testClass) {
        // TODO - do this inside some 'testng' suite, so that failures and logging are attached to 'testng' rather than some 'test worker'
        try {
            testClasses.add(applicationClassLoader.loadClass(testClass.getTestClassName()));
        } catch (Throwable e) {
            throw new BuildException(String.format("Could not load test class '%s'.", testClass.getTestClassName()), e);
        }
    }

    @Override
    public void stop() {
        try {
            runTests();
        } finally {
            resultProcessorActor.stop();
        }
    }

    @Override
    public void stopNow() {
        throw new UnsupportedOperationException("stopNow() should not be invoked on remote worker TestClassProcessor");
    }

    private void runTests() {
        TestNG testNg = new TestNG();
        testNg.setOutputDirectory(testReportDir.getAbsolutePath());
        testNg.setDefaultSuiteName(options.getDefaultSuiteName());
        testNg.setDefaultTestName(options.getDefaultTestName());
        if (options.getParallel() != null) {
            testNg.setParallel(options.getParallel());
        }
        if (options.getThreadCount() > 0) {
            testNg.setThreadCount(options.getThreadCount());
        }
        invokeVerifiedMethod(testNg, "setConfigFailurePolicy", String.class, options.getConfigFailurePolicy(), DEFAULT_CONFIG_FAILURE_POLICY);
        invokeVerifiedMethod(testNg, "setPreserveOrder", boolean.class, options.getPreserveOrder(), false);
        invokeVerifiedMethod(testNg, "setGroupByInstances", boolean.class, options.getGroupByInstances(), false);
        testNg.setUseDefaultListeners(options.getUseDefaultListeners());
        testNg.setVerbose(0);
        testNg.setGroups(CollectionUtils.join(",", options.getIncludeGroups()));
        testNg.setExcludedGroups(CollectionUtils.join(",", options.getExcludeGroups()));

        //adding custom test listeners before Gradle's listeners.
        //this way, custom listeners are more powerful and, for example, they can change test status.
        for (String listenerClass : options.getListeners()) {
            try {
                testNg.addListener(JavaReflectionUtil.newInstance(applicationClassLoader.loadClass(listenerClass)));
            } catch (Throwable e) {
                throw new BuildException(String.format("Could not add a test listener with class '%s'.", listenerClass), e);
            }
        }

        if (!options.getIncludedTests().isEmpty() || !options.getIncludedTestsCommandLine().isEmpty() || !options.getExcludedTests().isEmpty()) {
            testNg.addListener(new SelectedTestsFilter(options.getIncludedTests(),
                options.getExcludedTests(), options.getIncludedTestsCommandLine()));
        }

        if (!suiteFiles.isEmpty()) {
            testNg.setTestSuites(GFileUtils.toPaths(suiteFiles));
        } else {
            testNg.setTestClasses(testClasses.toArray(new Class<?>[0]));
        }
        testNg.addListener((Object) adaptListener(new TestNGTestResultProcessorAdapter(resultProcessor, idGenerator, clock)));
        testNg.run();
    }

    private void invokeVerifiedMethod(TestNG testNg, String methodName, Class<?> paramClass, Object value, Object defaultValue) {
        try {
            JavaMethod.of(TestNG.class, Object.class, methodName, paramClass).invoke(testNg, value);
        } catch (NoSuchMethodException e) {
            if (!value.equals(defaultValue)) {
                // Should not reach this point as this is validated in the test framework implementation - just propagate the failure
                throw e;
            }
        }
    }

    private ITestListener adaptListener(ITestListener listener) {
        TestNGListenerAdapterFactory factory = new TestNGListenerAdapterFactory(applicationClassLoader);
        return factory.createAdapter(listener);
    }

    private static class SelectedTestsFilter implements IMethodInterceptor {

        private final TestSelectionMatcher matcher;

        public SelectedTestsFilter(Set<String> includedTests, Set<String> excludedTests,
            Set<String> includedTestsCommandLine) {
            matcher = new TestSelectionMatcher(includedTests, excludedTests, includedTestsCommandLine);
        }

        @Override
        public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
            ISuite suite = context.getSuite();
            List<IMethodInstance> filtered = new LinkedList<IMethodInstance>();
            for (IMethodInstance candidate : methods) {
                if (matcher.matchesTest(candidate.getMethod().getTestClass().getName(), candidate.getMethod().getMethodName())
                    || matcher.matchesTest(suite.getName(), null)) {
                    filtered.add(candidate);
                }
            }
            return filtered;
        }
    }
}
