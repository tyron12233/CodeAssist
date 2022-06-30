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

package com.tyron.builder.api.reporting;

import com.google.common.collect.Sets;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.NamedDomainObjectSet;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.reporting.internal.BuildDashboardGenerator;
import com.tyron.builder.api.reporting.internal.DefaultBuildDashboardReports;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.Nested;
import com.tyron.builder.api.tasks.TaskAction;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.util.internal.ClosureBackedAction;
import com.tyron.builder.util.internal.CollectionUtils;
import com.tyron.builder.work.DisableCachingByDefault;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.inject.Inject;

import groovy.lang.Closure;

/**
 * Generates build dashboard report.
 */
@DisableCachingByDefault(because = "Not made cacheable, yet")
public class GenerateBuildDashboard extends DefaultTask implements Reporting<BuildDashboardReports> {
    private final Set<Reporting<? extends ReportContainer<?>>> aggregated =
            new LinkedHashSet<Reporting<? extends ReportContainer<?>>>();

    private final BuildDashboardReports reports;

    public GenerateBuildDashboard() {
        reports = getInstantiator().newInstance(DefaultBuildDashboardReports.class, this,
                getCollectionCallbackActionDecorator());
        reports.getHtml().getRequired().set(true);
    }

    @Inject
    protected Instantiator getInstantiator() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected CollectionCallbackActionDecorator getCollectionCallbackActionDecorator() {
        throw new UnsupportedOperationException();
    }

    @Input
    public Set<ReportState> getInputReports() {
        Set<ReportState> inputs = new LinkedHashSet<ReportState>();
        for (Report report : getEnabledInputReports()) {
            if (getReports().contains(report)) {
                // A report to be generated, ignore
                continue;
            }
            File outputLocation = report.getOutputLocation().get().getAsFile();
            inputs.add(new ReportState(report.getDisplayName(), outputLocation,
                    outputLocation.exists()));
        }
        return inputs;
    }

    private Set<Report> getEnabledInputReports() {
        HashSet<Reporting<? extends ReportContainer<?>>> allAggregatedReports =
                Sets.newHashSet(aggregated);
        allAggregatedReports.addAll(getAggregatedTasks());

        Set<NamedDomainObjectSet<? extends Report>> enabledReportSets = CollectionUtils
                .collect(allAggregatedReports,
                        new Transformer<NamedDomainObjectSet<? extends Report>, Reporting<?
                                extends ReportContainer<?>>>() {
                            @Override
                            public NamedDomainObjectSet<? extends Report> transform(Reporting<?
                                    extends ReportContainer<?>> reporting) {
                                return reporting.getReports().getEnabled();
                            }
                        });
        return new LinkedHashSet<Report>(
                CollectionUtils.flattenCollections(Report.class, enabledReportSets));
    }

    private Set<Reporting<? extends ReportContainer<?>>> getAggregatedTasks() {
        final Set<Reporting<? extends ReportContainer<?>>> reports = Sets.newHashSet();
        getProject().allprojects(new Action<BuildProject>() {
            @Override
            public void execute(BuildProject project) {
                project.getTasks().all(new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        if (!(task instanceof Reporting)) {
                            return;
                        }
                        reports.add(Cast.uncheckedNonnullCast(task));
                    }
                });
            }
        });
        return reports;
    }

    /**
     * Configures which reports are to be aggregated in the build dashboard report generated by
     * this task.
     *
     * <pre>
     * buildDashboard {
     *   aggregate codenarcMain, checkstyleMain
     * }
     * </pre>
     *
     * @param reportings an array of {@link Reporting} instances that are to be aggregated
     */
    public void aggregate(Reporting<? extends ReportContainer<?>>... reportings) {
        aggregated.addAll(Arrays.asList(reportings));
    }

    /**
     * The reports to be generated by this task.
     *
     * @return The reports container
     */
    @Nested
    @Override
    public BuildDashboardReports getReports() {
        return reports;
    }

    /**
     * Configures the reports to be generated by this task.
     * <p>
     * The contained reports can be configured by name and closures.
     *
     * <pre>
     * buildDashboard {
     *   reports {
     *     html {
     *       destination "build/dashboard.html"
     *     }
     *   }
     * }
     * </pre>
     *
     * @param closure The configuration
     * @return The reports container
     */
    @Override
    public BuildDashboardReports reports(Closure closure) {
        return reports(new ClosureBackedAction<>(closure));
    }

    /**
     * Configures the reports to be generated by this task.
     * <p>
     * The contained reports can be configured by name and closures.
     *
     * <pre>
     * buildDashboard {
     *   reports {
     *     html {
     *       destination "build/dashboard.html"
     *     }
     *   }
     * }
     * </pre>
     *
     * @param configureAction The configuration
     * @return The reports container
     */
    @Override
    public BuildDashboardReports reports(Action<? super BuildDashboardReports> configureAction) {
        configureAction.execute(reports);
        return reports;
    }

    @TaskAction
    void run() {
        if (getReports().getHtml().getRequired().get()) {
            BuildDashboardGenerator generator = new BuildDashboardGenerator();
            generator.render(getEnabledInputReports(), reports.getHtml().getEntryPoint());
        } else {
            setDidWork(false);
        }
    }

    private static class ReportState implements Serializable {
        private final String name;
        private final File destination;
        private final boolean available;

        private ReportState(String name, File destination, boolean available) {
            this.name = name;
            this.destination = destination;
            this.available = available;
        }

        @Override
        public boolean equals(Object obj) {
            ReportState other = (ReportState) obj;
            return name.equals(other.name) &&
                   destination.equals(other.destination) &&
                   available == other.available;
        }

        @Override
        public int hashCode() {
            return name.hashCode() ^ destination.hashCode();
        }
    }
}
