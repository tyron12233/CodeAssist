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

package com.tyron.builder.testing.base.plugins;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Task;
import com.tyron.builder.language.base.plugins.ComponentModelBasePlugin;
import com.tyron.builder.model.Finalize;
import com.tyron.builder.model.Model;
import com.tyron.builder.model.ModelMap;
import com.tyron.builder.model.Mutate;
import com.tyron.builder.model.Path;
import com.tyron.builder.model.RuleSource;
import com.tyron.builder.platform.base.BinaryContainer;
import com.tyron.builder.platform.base.BinarySpec;
import com.tyron.builder.platform.base.ComponentType;
import com.tyron.builder.platform.base.TypeBuilder;
import com.tyron.builder.platform.base.internal.BinarySpecInternal;
import com.tyron.builder.testing.base.TestSuiteBinarySpec;
import com.tyron.builder.testing.base.TestSuiteContainer;
import com.tyron.builder.testing.base.TestSuiteSpec;
import com.tyron.builder.testing.base.TestSuiteTaskCollection;
import com.tyron.builder.testing.base.internal.BaseTestSuiteSpec;

/**
 * Base plugin for testing.
 *
 * - Adds a {@link com.tyron.builder.testing.base.TestSuiteContainer} named {@code testSuites} to the model.
 * - Copies test binaries from {@code testSuites} into {@code binaries}.
 */
@Incubating
public class TestingModelBasePlugin implements Plugin<BuildProject> {
    @Override
    public void apply(BuildProject project) {
        project.getPluginManager().apply(ComponentModelBasePlugin.class);
    }

    static class Rules extends RuleSource {
        @ComponentType
        void registerTestSuiteSpec(TypeBuilder<TestSuiteSpec> builder) {
            builder.defaultImplementation(BaseTestSuiteSpec.class);
        }

        @Model
        void testSuites(TestSuiteContainer testSuites) {
        }

        @Mutate
        void copyTestBinariesToGlobalContainer(BinaryContainer binaries, TestSuiteContainer testSuites) {
            for (TestSuiteSpec testSuite : testSuites.values()) {
                for (BinarySpecInternal binary : testSuite.getBinaries().withType(BinarySpecInternal.class).values()) {
                    binaries.put(binary.getProjectScopedName(), binary);
                }
            }
        }

        @Finalize
        void linkTestSuiteBinariesRunTaskToBinariesCheckTasks(@Path("binaries") ModelMap<TestSuiteBinarySpec> binaries) {
            binaries.afterEach(new Action<TestSuiteBinarySpec>() {
                @Override
                public void execute(TestSuiteBinarySpec testSuiteBinary) {
                    if (testSuiteBinary.isBuildable()) {
                        if (testSuiteBinary.getTasks() instanceof TestSuiteTaskCollection) {
                            testSuiteBinary.checkedBy(((TestSuiteTaskCollection) testSuiteBinary.getTasks()).getRun());
                        }
                        BinarySpec testedBinary = testSuiteBinary.getTestedBinary();
                        if (testedBinary != null && testedBinary.isBuildable()) {
                            testedBinary.checkedBy(testSuiteBinary.getCheckTask());
                        }
                    }
                }
            });
        }

        @Finalize
        void attachBinariesCheckTasksToCheckLifecycle(@Path("tasks.check") Task checkTask, @Path("binaries") ModelMap<BinarySpec> binaries) {
            for (BinarySpec binary : binaries) {
                if (binary.isBuildable()) {
                    Task binaryCheckTask = binary.getCheckTask();
                    if (binaryCheckTask != null) {
                        checkTask.dependsOn(binaryCheckTask);
                    }
                }
            }
        }
    }
}
