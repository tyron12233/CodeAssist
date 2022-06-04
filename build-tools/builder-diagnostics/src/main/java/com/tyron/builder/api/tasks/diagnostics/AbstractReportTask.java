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
package com.tyron.builder.api.tasks.diagnostics;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.internal.ConventionTask;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Optional;
import com.tyron.builder.api.tasks.OutputFile;
import com.tyron.builder.api.tasks.TaskAction;
import com.tyron.builder.api.tasks.diagnostics.internal.ReportGenerator;
import com.tyron.builder.api.tasks.diagnostics.internal.ReportRenderer;
import com.tyron.builder.initialization.BuildClientMetaData;
import com.tyron.builder.internal.logging.ConsoleRenderer;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * The base class for all project report tasks.
 * <p>
 * Preserved for backward compatibility.
 *
 * @deprecated Use {@link ProjectBasedReportTask} instead.
 */
@Deprecated
@DisableCachingByDefault(because = "Abstract super-class, not to be instantiated directly")
public abstract class AbstractReportTask extends ConventionTask {
    private File outputFile;

    // todo annotate as required
    private Set<BuildProject> projects;

    protected AbstractReportTask() {
        getOutputs().upToDateWhen(element -> false);
        projects = new HashSet<>();
        projects.add(getProject());
    }

    @Inject
    protected BuildClientMetaData getClientMetaData() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected StyledTextOutputFactory getTextOutputFactory() {
        throw new UnsupportedOperationException();
    }

    @TaskAction
    public void generate() {
        reportGenerator().generateReport(new TreeSet<>(getProjects()), project -> {
            generate(project);
            logClickableOutputFileUrl();
        });
    }

    ReportGenerator reportGenerator() {
        return new ReportGenerator(getRenderer(), getClientMetaData(), getOutputFile(),
                getTextOutputFactory());
    }

    void logClickableOutputFileUrl() {
        if (shouldCreateReportFile()) {
            getLogger().lifecycle("See the report at: {}", clickableOutputFileUrl());
        }
    }

    String clickableOutputFileUrl() {
        return new ConsoleRenderer().asClickableFileUrl(getOutputFile());
    }

    boolean shouldCreateReportFile() {
        return getOutputFile() != null;
    }

    @Internal
    protected abstract ReportRenderer getRenderer();

    protected abstract void generate(BuildProject project) throws IOException;

    /**
     * Returns the file which the report will be written to. When set to {@code null}, the report
     * is written to {@code System.out}.
     * Defaults to {@code null}.
     *
     * @return The output file. May be null.
     */
    @Nullable
    @Optional
    @OutputFile
    public File getOutputFile() {
        return outputFile;
    }

    /**
     * Sets the file which the report will be written to. Set this to {@code null} to write the
     * report to {@code System.out}.
     *
     * @param outputFile The output file. May be null.
     */
    public void setOutputFile(@Nullable File outputFile) {
        this.outputFile = outputFile;
    }

    /**
     * Returns the set of project to generate this report for. By default, the report is
     * generated for the task's
     * containing project.
     *
     * @return The set of files.
     */
    @Internal
    // TODO:LPTR Have the paths of the projects serve as @Input maybe?
    public Set<BuildProject> getProjects() {
        return projects;
    }

    /**
     * Specifies the set of projects to generate this report for.
     *
     * @param projects The set of projects. Must not be null.
     */
    public void setProjects(Set<BuildProject> projects) {
        this.projects = projects;
    }
}
