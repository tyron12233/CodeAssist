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

package com.tyron.builder.testing.base.plugins;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.plugins.ReportingBasePlugin;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.reporting.ReportingExtension;
import com.tyron.builder.api.tasks.testing.AbstractTestTask;

import java.io.File;

/**
 * Base plugin for testing.
 * <p>
 * - Adds default locations for test reporting
 *
 * @since 4.4
 */
public class TestingBasePlugin implements Plugin<BuildProject> {
    public static final String TEST_RESULTS_DIR_NAME = "test-results";
    public static final String TESTS_DIR_NAME = "tests";
    private static final Transformer<File, Directory> TO_FILE_TRANSFORMER =
            new Transformer<File, Directory>() {
                @Override
                public File transform(Directory directory) {
                    return directory.getAsFile();
                }
            };

    @Override
    public void apply(final BuildProject project) {
        project.getPluginManager().apply(ReportingBasePlugin.class);
        project.getTasks().withType(AbstractTestTask.class, new Action<AbstractTestTask>() {
            @Override
            public void execute(final AbstractTestTask test) {
                test.getReports().getHtml()
                        .setDestination(getTestReportsDir(project, test).map(TO_FILE_TRANSFORMER));
                test.getReports().getJunitXml()
                        .setDestination(getTestResultsDir(project, test).map(TO_FILE_TRANSFORMER));
                test.getBinaryResultsDirectory().set(getTestResultsDir(project, test)
                        .map(new Transformer<Directory, Directory>() {
                            @Override
                            public Directory transform(Directory directory) {
                                return directory.dir("binary");
                            }
                        }));
            }
        });
    }

    private Provider<Directory> getTestResultsDir(BuildProject project, AbstractTestTask test) {
        return project.getLayout().getBuildDirectory()
                .dir(TEST_RESULTS_DIR_NAME + "/" + test.getName());
    }

    private Provider<Directory> getTestReportsDir(BuildProject project,
                                                  final AbstractTestTask test) {
        DirectoryProperty baseDirectory =
                project.getExtensions().getByType(ReportingExtension.class).getBaseDirectory();
        return baseDirectory.dir(TESTS_DIR_NAME + "/" + test.getName());
    }
}
