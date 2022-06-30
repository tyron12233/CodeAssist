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

package com.tyron.builder.api.tasks.testing;

import com.tyron.builder.api.reporting.DirectoryReport;
import com.tyron.builder.api.reporting.Report;
import com.tyron.builder.api.reporting.ReportContainer;
import com.tyron.builder.api.tasks.Internal;

/**
 * The reports produced by the {@link Test} task.
 */
public interface TestTaskReports extends ReportContainer<Report> {

    /**
     * A HTML report indicate the results of the test execution.
     *
     * @return The HTML report
     */
    @Internal
    DirectoryReport getHtml();

    /**
     * The test results in “JUnit XML” format.
     *
     * @return The test results in “JUnit XML” format
     */
    @Internal
    JUnitXmlReport getJunitXml();

}
