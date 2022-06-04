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

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.internal.IoActions;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationQueue;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.Timer;
import com.tyron.builder.util.internal.GFileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;

public class Binary2JUnitXmlReportGenerator {

    private final File testResultsDir;
    private final TestResultsProvider testResultsProvider;

    @VisibleForTesting
    JUnitXmlResultWriter xmlWriter;

    private final BuildOperationExecutor buildOperationExecutor;
    private final static Logger LOG = Logging.getLogger(Binary2JUnitXmlReportGenerator.class);

    public Binary2JUnitXmlReportGenerator(File testResultsDir,
                                          TestResultsProvider testResultsProvider,
                                          JUnitXmlResultOptions options,
                                          BuildOperationExecutor buildOperationExecutor,
                                          String hostName) {
        this.testResultsDir = testResultsDir;
        this.testResultsProvider = testResultsProvider;
        this.xmlWriter = new JUnitXmlResultWriter(hostName, testResultsProvider, options);
        this.buildOperationExecutor = buildOperationExecutor;
    }

    public void generate() {
        Timer clock = Time.startTimer();

        buildOperationExecutor.run(new RunnableBuildOperation() {
            @Override
            public void run(BuildOperationContext context) {
                File[] oldXmlFiles = testResultsDir.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        return name.startsWith("TEST") && name.endsWith(".xml");
                    }
                });

                for (File oldXmlFile : oldXmlFiles) {
                    GFileUtils.deleteQuietly(oldXmlFile);
                }
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName("Delete old JUnit XML results");
            }
        });

        buildOperationExecutor
                .runAll(new Action<BuildOperationQueue<JUnitXmlReportFileGenerator>>() {
                    @Override
                    public void execute(final BuildOperationQueue<JUnitXmlReportFileGenerator> queue) {
                        testResultsProvider.visitClasses(new Action<TestClassResult>() {
                            @Override
                            public void execute(final TestClassResult result) {
                                final File reportFile =
                                        new File(testResultsDir, getReportFileName(result));
                                queue.add(new JUnitXmlReportFileGenerator(result, reportFile,
                                        xmlWriter));
                            }
                        });
                    }
                });

        LOG.info("Finished generating test XML results ({}) into: {}", clock.getElapsed(),
                testResultsDir);
    }

    private String getReportFileName(TestClassResult result) {
        return "TEST-" + GFileUtils.toSafeFileName(result.getClassName()) + ".xml";
    }

    private static class JUnitXmlReportFileGenerator implements RunnableBuildOperation {
        private final TestClassResult result;
        private final File reportFile;
        private final JUnitXmlResultWriter xmlWriter;

        public JUnitXmlReportFileGenerator(TestClassResult result,
                                           File reportFile,
                                           JUnitXmlResultWriter xmlWriter) {
            this.result = result;
            this.reportFile = reportFile;
            this.xmlWriter = xmlWriter;
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor.displayName(
                    "Generate junit XML test report for ".concat(result.getClassName()));
        }

        @Override
        public void run(BuildOperationContext context) {
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(reportFile);
                xmlWriter.write(result, output);
                output.close();
            } catch (Exception e) {
                throw new BuildException(
                        String.format("Could not write XML test results for %s to file %s.",
                                result.getClassName(), reportFile), e);
            } finally {
                IoActions.closeQuietly(output);
            }
        }
    }
}
