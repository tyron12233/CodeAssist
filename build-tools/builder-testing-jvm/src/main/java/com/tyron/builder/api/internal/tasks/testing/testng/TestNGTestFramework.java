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

import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.plugins.DslObject;
import com.tyron.builder.api.internal.tasks.testing.TestClassLoaderFactory;
import com.tyron.builder.api.internal.tasks.testing.TestClassProcessor;
import com.tyron.builder.api.internal.tasks.testing.TestFramework;
import com.tyron.builder.api.internal.tasks.testing.WorkerTestClassProcessorFactory;
import com.tyron.builder.api.internal.tasks.testing.detection.ClassFileExtractionManager;
import com.tyron.builder.api.internal.tasks.testing.filter.DefaultTestFilter;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.reporting.DirectoryReport;
import com.tyron.builder.api.tasks.testing.Test;
import com.tyron.builder.api.tasks.testing.testng.TestNGOptions;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.actor.ActorFactory;
import com.tyron.builder.internal.id.IdGenerator;
import com.tyron.builder.internal.scan.UsedByScanPlugin;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.process.internal.worker.WorkerProcessBuilder;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

@UsedByScanPlugin("test-retry")
public class TestNGTestFramework implements TestFramework {
    private final TestNGOptions options;
    private TestNGDetector detector;
    private final DefaultTestFilter filter;
    private final ObjectFactory objects;
    private final String testTaskPath;
    private final FileCollection testTaskClasspath;
    private final Factory<File> testTaskTemporaryDir;
    private transient ClassLoader testClassLoader;

    @UsedByScanPlugin("test-retry")
    public TestNGTestFramework(final Test testTask, FileCollection classpath, DefaultTestFilter filter, ObjectFactory objects) {
        this.filter = filter;
        this.objects = objects;
        this.testTaskPath = testTask.getPath();
        this.testTaskClasspath = classpath;
        this.testTaskTemporaryDir = testTask.getTemporaryDirFactory();
        options = objects.newInstance(TestNGOptions.class);
        conventionMapOutputDirectory(options, testTask.getReports().getHtml());
        detector = new TestNGDetector(new ClassFileExtractionManager(testTask.getTemporaryDirFactory()));
    }

    private static void conventionMapOutputDirectory(TestNGOptions options, final DirectoryReport html) {
        new DslObject(options).getConventionMapping().map("outputDirectory", new Callable<File>() {
            @Override
            public File call() {
                return html.getOutputLocation().getAsFile().getOrNull();
            }
        });
    }

    @Override
    public WorkerTestClassProcessorFactory getProcessorFactory() {
        verifyConfigFailurePolicy();
        verifyPreserveOrder();
        verifyGroupByInstances();
        List<File> suiteFiles = options.getSuites(testTaskTemporaryDir.create());
        TestNGSpec spec = new TestNGSpec(options, filter);
        return new TestClassProcessorFactoryImpl(this.options.getOutputDirectory(), spec, suiteFiles);
    }

    private void verifyConfigFailurePolicy() {
        if (!options.getConfigFailurePolicy().equals(TestNGOptions.DEFAULT_CONFIG_FAILURE_POLICY)) {
            verifyMethodExists("setConfigFailurePolicy", String.class,
                String.format("The version of TestNG used does not support setting config failure policy to '%s'.", options.getConfigFailurePolicy()));
        }
    }

    private void verifyPreserveOrder() {
        if (options.getPreserveOrder()) {
            verifyMethodExists("setPreserveOrder", boolean.class, "Preserving the order of tests is not supported by this version of TestNG.");
        }
    }

    private void verifyGroupByInstances() {
        if (options.getGroupByInstances()) {
            verifyMethodExists("setGroupByInstances", boolean.class, "Grouping tests by instances is not supported by this version of TestNG.");
        }
    }

    private void verifyMethodExists(String methodName, Class<?> parameterType, String failureMessage) {
        try {
            createTestNg().getMethod(methodName, parameterType);
        } catch (NoSuchMethodException e) {
            throw new InvalidUserDataException(failureMessage, e);
        }
    }

    private Class<?> createTestNg() {
        if (testClassLoader == null) {
            TestClassLoaderFactory factory = objects.newInstance(
                TestClassLoaderFactory.class,
                testTaskPath,
                testTaskClasspath
            );
            testClassLoader = factory.create();
        }
        try {
            return testClassLoader.loadClass("org.testng.TestNG");
        } catch (ClassNotFoundException e) {
            throw new BuildException("Could not load TestNG.", e);
        }
    }

    @Override
    public Action<WorkerProcessBuilder> getWorkerConfigurationAction() {
        return new Action<WorkerProcessBuilder>() {
            @Override
            public void execute(WorkerProcessBuilder workerProcessBuilder) {
                workerProcessBuilder.sharedPackages("org.testng");
            }
        };
    }

    @Override
    public List<String> getTestWorkerImplementationModules() {
        return Collections.emptyList();
    }

    @Override
    public TestNGOptions getOptions() {
        return options;
    }

    @Override
    public TestNGDetector getDetector() {
        return detector;
    }

    @Override
    public void close() throws IOException {
        // Clear expensive state from the test framework to avoid holding on to memory
        // This should probably be a part of the test task and managed there.
        testClassLoader = null;
        detector = null;
    }

    private static class TestClassProcessorFactoryImpl implements WorkerTestClassProcessorFactory, Serializable {
        private final File testReportDir;
        private final TestNGSpec options;
        private final List<File> suiteFiles;

        public TestClassProcessorFactoryImpl(File testReportDir, TestNGSpec options, List<File> suiteFiles) {
            this.testReportDir = testReportDir;
            this.options = options;
            this.suiteFiles = suiteFiles;
        }

        @Override
        public TestClassProcessor create(ServiceRegistry serviceRegistry) {
            return new TestNGTestClassProcessor(testReportDir, options, suiteFiles, serviceRegistry.get(IdGenerator.class), serviceRegistry.get(Clock.class), serviceRegistry.get(ActorFactory.class));
        }
    }
}
