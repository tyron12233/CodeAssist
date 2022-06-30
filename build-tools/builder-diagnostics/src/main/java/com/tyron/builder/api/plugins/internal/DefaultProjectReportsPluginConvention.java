/*
 * Copyright 2018 the original author or authors.
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

package com.tyron.builder.api.plugins.internal;

import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.reflect.HasPublicType;
import com.tyron.builder.api.reflect.TypeOf;
import com.tyron.builder.api.reporting.ReportingExtension;
import com.tyron.builder.util.internal.WrapUtil;

import java.io.File;
import java.util.Set;

import static com.tyron.builder.api.reflect.TypeOf.typeOf;

@Deprecated
@NonNullApi
public class DefaultProjectReportsPluginConvention extends com.tyron.builder.api.plugins.ProjectReportsPluginConvention implements HasPublicType {
    private String projectReportDirName = "project";
    private final BuildProject project;

    public DefaultProjectReportsPluginConvention(BuildProject project) {
        this.project = project;
    }

    @Override
    public TypeOf<?> getPublicType() {
        return typeOf(com.tyron.builder.api.plugins.ProjectReportsPluginConvention .class);
    }

    @Override
    public String getProjectReportDirName() {
        return projectReportDirName;
    }

    @Override
    public void setProjectReportDirName(String projectReportDirName) {
        this.projectReportDirName = projectReportDirName;
    }

    @Override
    public File getProjectReportDir() {
        return project.getExtensions().getByType(ReportingExtension.class).file(projectReportDirName);
    }

    @Override
    public Set<BuildProject> getProjects() {
        return WrapUtil.toSet(project);
    }
}
