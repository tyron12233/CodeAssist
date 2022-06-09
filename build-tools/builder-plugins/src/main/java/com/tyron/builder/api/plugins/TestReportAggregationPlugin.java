/*
 * Copyright 2021 the original author or authors.
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

package com.tyron.builder.api.plugins;

import com.tyron.builder.api.ExtensiblePolymorphicDomainObjectContainer;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.attributes.Category;
import com.tyron.builder.api.attributes.DocsType;
import com.tyron.builder.api.attributes.TestType;
import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.tasks.testing.DefaultAggregateTestReport;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.plugins.jvm.JvmTestSuite;
import com.tyron.builder.api.reporting.ReportingExtension;
import com.tyron.builder.api.tasks.testing.AggregateTestReport;
import com.tyron.builder.testing.base.TestSuite;
import com.tyron.builder.testing.base.TestingExtension;
import com.tyron.builder.testing.base.plugins.TestingBasePlugin;

import java.util.concurrent.Callable;

/**
 * Adds configurations to for resolving variants containing test execution results, which may span multiple subprojects.  Reacts to the presence of the jvm-test-suite plugin and creates
 * tasks to collect test results for each named test-suite.
 *
 * @since 7.4
 * @see <a href="https://docs.gradle.org/current/userguide/test_report_aggregation_plugin.html">Test Report Aggregation Plugin reference</a>
 */
@Incubating
public abstract class TestReportAggregationPlugin implements Plugin<BuildProject> {

    public static final String TEST_REPORT_AGGREGATION_CONFIGURATION_NAME = "testReportAggregation";

    @Override
    public void apply(BuildProject project) {
        project.getPluginManager().apply("org.gradle.reporting-base");

        final Configuration testAggregation = project.getConfigurations().create(TEST_REPORT_AGGREGATION_CONFIGURATION_NAME);
        testAggregation.setDescription("A configuration to collect test execution results");
        testAggregation.setVisible(false);
        testAggregation.setCanBeConsumed(false);
        testAggregation.setCanBeResolved(false);

        ReportingExtension reporting = project.getExtensions().getByType(ReportingExtension.class);
        reporting.getReports().registerBinding(AggregateTestReport.class, DefaultAggregateTestReport.class);

        ObjectFactory objects = project.getObjects();

        // prepare testReportDir with a reasonable default, but override with JavaPluginExtension#testReportDir if available
        final DirectoryProperty testReportDir = objects.directoryProperty().convention(reporting.getBaseDirectory().dir(TestingBasePlugin.TESTS_DIR_NAME));
        JavaPluginExtension javaPluginExtension = project.getExtensions().findByType(JavaPluginExtension.class);
        if (javaPluginExtension != null) {
            testReportDir.set(javaPluginExtension.getTestReportDir());
        }

        // iterate and configure each user-specified report, creating a <reportName>ExecutionData configuration for each
        reporting.getReports().withType(AggregateTestReport.class).configureEach(report -> {
            report.getReportTask().configure(task -> {

                // A resolvable configuration to collect test results; typically named "testResults"
                Configuration testResultsConf = project.getConfigurations().create(report.getName() + "Results");
                testResultsConf.extendsFrom(testAggregation);
                testResultsConf.setDescription(String.format("Supplies test result data to the %s.  External library dependencies may appear as resolution failures, but this is expected behavior.", report.getName()));
                testResultsConf.setVisible(false);
                testResultsConf.setCanBeConsumed(false);
                testResultsConf.setCanBeResolved(true);
                testResultsConf.attributes(attributes -> {
                    attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.DOCUMENTATION));
                    attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.class, DocsType.TEST_RESULTS));
                    attributes.attributeProvider(TestType.TEST_TYPE_ATTRIBUTE, report.getTestType().map(tt -> objects.named(TestType.class, tt)));
                    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.VERIFICATION));
                });

                Callable<FileCollection> testResults = () ->
                    testResultsConf.getIncoming().artifactView(view -> {
                        view.componentFilter(id -> id instanceof ProjectComponentIdentifier);
                        view.lenient(true);
                    }).getFiles();

                task.getTestResults().from(testResults);
                task.getDestinationDirectory().convention(testReportDir.dir(report.getTestType().map(tt -> tt + "/aggregated-results")));
            });
        });

        // convention for synthesizing reports based on existing test suites in "this" project
        project.getPlugins().withId("jvm-test-suite", plugin -> {
            // Depend on this project for aggregation
            project.getDependencies().add(TEST_REPORT_AGGREGATION_CONFIGURATION_NAME, project);

            TestingExtension testing = project.getExtensions().getByType(TestingExtension.class);
            ExtensiblePolymorphicDomainObjectContainer<TestSuite> testSuites = testing.getSuites();

            testSuites.withType(JvmTestSuite.class).configureEach(testSuite -> {
                reporting.getReports().create(testSuite.getName() + "AggregateTestReport", AggregateTestReport.class, report -> {
                    report.getTestType().convention(testSuite.getTestType());
                });
            });
        });
    }

}
