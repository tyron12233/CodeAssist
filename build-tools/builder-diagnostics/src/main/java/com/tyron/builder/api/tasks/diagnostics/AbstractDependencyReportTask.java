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
package com.tyron.builder.api.tasks.diagnostics;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.diagnostics.internal.ConfigurationFinder;
import com.tyron.builder.api.tasks.diagnostics.internal.DependencyReportRenderer;
import com.tyron.builder.api.tasks.diagnostics.internal.ReportRenderer;
import com.tyron.builder.api.tasks.diagnostics.internal.dependencies.AsciiDependencyReportRenderer;
import com.tyron.builder.api.tasks.options.Option;
import com.tyron.builder.internal.deprecation.DeprecatableConfiguration;
import com.tyron.builder.work.DisableCachingByDefault;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Displays the dependency tree for a configuration.
 */
@DisableCachingByDefault(because = "Abstract super-class, not to be instantiated directly")
public abstract class AbstractDependencyReportTask extends ProjectBasedReportTask {

    private DependencyReportRenderer renderer = new AsciiDependencyReportRenderer();

    private Set<Configuration> configurations;

    @Override
    public ReportRenderer getRenderer() {
        return renderer;
    }

    /**
     * Set the renderer to use to build a report. If unset, AsciiGraphRenderer will be used.
     */
    public void setRenderer(DependencyReportRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public void generate(BuildProject project) throws IOException {
        SortedSet<Configuration> sortedConfigurations = new TreeSet<>(Comparator.comparing(Configuration::getName));
        sortedConfigurations.addAll(getReportConfigurations());
        for (Configuration configuration : sortedConfigurations) {
            renderer.startConfiguration(configuration);
            renderer.render(configuration);
            renderer.completeConfiguration(configuration);
        }
    }

    private Set<Configuration> getReportConfigurations() {
        return configurations != null ? configurations : getNonDeprecatedTaskConfigurations();
    }

    /**
     * Returns the configurations to generate the report for. Defaults to all configurations of this task's containing
     * project.
     *
     * @return the configurations.
     */
    @Internal
    public Set<Configuration> getConfigurations() {
        return configurations;
    }

    /**
     * Sets the configurations to generate the report for.
     *
     * @param configurations The configuration. Must not be null.
     */
    public void setConfigurations(Set<Configuration> configurations) {
        this.configurations = configurations;
    }

    /**
     * Sets the single configuration (by name) to generate the report for.
     *
     * @param configurationName name of the configuration to generate the report for
     */
    @Option(option = "configuration", description = "The configuration to generate the report for.")
    public void setConfiguration(String configurationName) {
        this.configurations = Collections.singleton(ConfigurationFinder.find(getTaskConfigurations(), configurationName));
    }

    private Set<Configuration> getNonDeprecatedTaskConfigurations() {
        Set<Configuration> filteredConfigurations = new HashSet<>();
        for (Configuration configuration : getTaskConfigurations()) {
            if (!((DeprecatableConfiguration) configuration).isFullyDeprecated()) {
                filteredConfigurations.add(configuration);
            }
        }
        return filteredConfigurations;
    }

    @Internal
    public abstract ConfigurationContainer getTaskConfigurations();
}
