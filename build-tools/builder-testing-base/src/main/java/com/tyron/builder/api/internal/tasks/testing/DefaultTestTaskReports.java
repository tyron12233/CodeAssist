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

package com.tyron.builder.api.internal.tasks.testing;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.reporting.ConfigurableReport;
import com.tyron.builder.api.reporting.DirectoryReport;
import com.tyron.builder.api.reporting.Report;
import com.tyron.builder.api.reporting.internal.TaskGeneratedSingleDirectoryReport;
import com.tyron.builder.api.reporting.internal.TaskReportContainer;
import com.tyron.builder.api.tasks.testing.JUnitXmlReport;
import com.tyron.builder.api.tasks.testing.TestTaskReports;

import javax.inject.Inject;

public class DefaultTestTaskReports extends TaskReportContainer<Report> implements TestTaskReports {

    @Inject
    public DefaultTestTaskReports(Task task, ObjectFactory objectFactory, CollectionCallbackActionDecorator callbackActionDecorator) {
        super(ConfigurableReport.class, task, callbackActionDecorator);

        add(DefaultJUnitXmlReport.class, "junitXml", task, objectFactory);
        add(TaskGeneratedSingleDirectoryReport.class, "html", task, "index.html");
    }

    @Override
    public DirectoryReport getHtml() {
        return (DirectoryReport) getByName("html");
    }

    @Override
    public JUnitXmlReport getJunitXml() {
        return (JUnitXmlReport) getByName("junitXml");
    }

}
