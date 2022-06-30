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

import static com.tyron.builder.api.reflect.TypeOf.typeOf;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.JavaVersion;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.java.archives.Manifest;
import com.tyron.builder.api.plugins.JavaPluginConvention;
import com.tyron.builder.api.plugins.JavaPluginExtension;
import com.tyron.builder.api.reflect.HasPublicType;
import com.tyron.builder.api.reflect.TypeOf;
import com.tyron.builder.api.reporting.ReportingExtension;
import com.tyron.builder.api.tasks.SourceSetContainer;
import com.tyron.builder.util.RelativePathUtil;

import java.io.File;

import groovy.lang.Closure;

public class DefaultJavaPluginConvention extends JavaPluginConvention implements HasPublicType {

    private final ProjectInternal project;
    private final JavaPluginExtension extension;

    public DefaultJavaPluginConvention(ProjectInternal project, JavaPluginExtension extension) {
        this.project = project;
        this.extension = extension;
    }

    @Override
    public TypeOf<?> getPublicType() {
        return typeOf(JavaPluginConvention.class);
    }

    @Override
    public Object sourceSets(Closure closure) {
        return extension.sourceSets(closure);
    }

    @Override
    public File getDocsDir() {
        return extension.getDocsDir().get().getAsFile();
    }

    @Override
    public File getTestResultsDir() {
        return extension.getTestResultsDir().get().getAsFile();
    }

    @Override
    public File getTestReportDir() {
        return extension.getTestReportDir().get().getAsFile();
    }

    @Override
    public JavaVersion getSourceCompatibility() {
        return extension.getSourceCompatibility();
    }

    @Override
    public void setSourceCompatibility(Object value) {
        extension.setSourceCompatibility(value);
    }

    @Override
    public void setSourceCompatibility(JavaVersion value) {
        extension.setSourceCompatibility(value);
    }

    @Override
    public JavaVersion getTargetCompatibility() {
        return extension.getTargetCompatibility();
    }

    @Override
    public void setTargetCompatibility(Object value) {
        extension.setTargetCompatibility(value);
    }

    @Override
    public void setTargetCompatibility(JavaVersion value) {
        extension.setTargetCompatibility(value);
    }

    @Override
    public Manifest manifest() {
        return extension.manifest();
    }

    @Override
    public Manifest manifest(Closure closure) {
        return extension.manifest(closure);
    }

    @Override
    public Manifest manifest(Action<? super Manifest> action) {
        return extension.manifest(action);
    }

    @Override
    public String getDocsDirName() {
        return relativePath(project.getLayout().getBuildDirectory(), extension.getDocsDir());
    }

    @Override
    public void setDocsDirName(String docsDirName) {
        extension.getDocsDir().set(project.getLayout().getBuildDirectory().dir(docsDirName));
    }

    @Override
    public String getTestResultsDirName() {
        return relativePath(project.getLayout().getBuildDirectory(), extension.getTestResultsDir());
    }

    @Override
    public void setTestResultsDirName(String testResultsDirName) {
        extension.getTestResultsDir()
                .set(project.getLayout().getBuildDirectory().dir(testResultsDirName));
    }

    @Override
    public String getTestReportDirName() {
        return relativePath(
                project.getExtensions().getByType(ReportingExtension.class).getBaseDirectory(),
                extension.getTestReportDir());
    }

    @Override
    public void setTestReportDirName(String testReportDirName) {
        extension.getTestReportDir()
                .set(project.getExtensions().getByType(ReportingExtension.class)
                .getBaseDirectory()
                        .dir(testReportDirName));
    }

    @Override
    public SourceSetContainer getSourceSets() {
        return extension.getSourceSets();
    }

    @Override
    public ProjectInternal getProject() {
        return project;
    }

    @Override
    public void disableAutoTargetJvm() {
        extension.disableAutoTargetJvm();
    }

    @Override
    public boolean getAutoTargetJvmDisabled() {
        return extension.getAutoTargetJvmDisabled();
    }

    private File getReportsDir() {
        // This became public API by accident as Groovy has access to private methods and we show
        // an example in our docs
        // see subprojects/docs/src/snippets/java/customDirs/groovy/build.gradle
        // and https://docs.gradle.org/current/userguide/java_testing.html#test_reporting
        return project.getExtensions().getByType(ReportingExtension.class).getBaseDir();
    }


    private static String relativePath(DirectoryProperty from, DirectoryProperty to) {
        return RelativePathUtil.relativePath(from.get().getAsFile(), to.get().getAsFile());
    }
}
