/*
 * Copyright 2008 the original author or authors.
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

import static java.util.Collections.emptyList;

import com.google.common.base.Strings;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.api.internal.project.ProjectTaskLister;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.tasks.Console;
import com.tyron.builder.api.tasks.TaskAction;
import com.tyron.builder.api.tasks.diagnostics.internal.AggregateMultiProjectTaskReportModel;
import com.tyron.builder.api.tasks.diagnostics.internal.DefaultGroupTaskReportModel;
import com.tyron.builder.api.tasks.diagnostics.internal.ProjectDetails;
import com.tyron.builder.api.tasks.diagnostics.internal.ReportRenderer;
import com.tyron.builder.api.tasks.diagnostics.internal.RuleDetails;
import com.tyron.builder.api.tasks.diagnostics.internal.SingleProjectTaskReportModel;
import com.tyron.builder.api.tasks.diagnostics.internal.TaskDetails;
import com.tyron.builder.api.tasks.diagnostics.internal.TaskDetailsFactory;
import com.tyron.builder.api.tasks.diagnostics.internal.TaskReportRenderer;
import com.tyron.builder.api.tasks.options.Option;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.serialization.Cached;
import com.tyron.builder.work.DisableCachingByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * <p>Displays a list of tasks in the project. An instance of this type is used when you execute
 * the {@code tasks} task
 * from the command-line.</p>
 * <p>
 * By default, this report shows only those tasks which have been assigned to a task group,
 * so-called <i>visible</i>
 * tasks. Tasks which have not been assigned to a task group, so-called <i>hidden</i> tasks, can
 * be included in the report
 * by enabling the command line option {@code --all}.
 */
@DisableCachingByDefault(because = "Not worth caching")
public class TaskReportTask extends ConventionReportTask {

    private boolean detail;
    private final Property<Boolean> showTypes = getProject().getObjects().property(Boolean.class);
    private String group;
    private final Cached<TaskReportModel> model = Cached.of(this::computeTaskReportModel);
    private transient TaskReportRenderer renderer;

    @Override
    public ReportRenderer getRenderer() {
        if (renderer == null) {
            renderer = new TaskReportRenderer();
        }
        return renderer;
    }

    public void setRenderer(TaskReportRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Sets whether to show "invisible" tasks without a group or dependent tasks.
     * <p>
     * This property can be set via command-line option '--all'.
     */
    @Option(option = "all", description = "Show additional tasks and detail.")
    public void setShowDetail(boolean detail) {
        this.detail = detail;
    }

    // TODO config-cache - should invalidate the cache or the filtering and merging should be
    //  moved to task execution time
    @Console
    public boolean isDetail() {
        return detail;
    }

    /**
     * Set a specific task group to be displayed.
     *
     * @since 5.1
     */
    @Option(option = "group", description = "Show tasks for a specific group.")
    public void setDisplayGroup(String group) {
        this.group = group;
    }

    /**
     * Returns the task group to be displayed.
     * <p>
     * This property can be set via command-line option '--group'.
     *
     * @since 5.1
     */
    @Console
    public String getDisplayGroup() {
        return group;
    }

    /**
     * Whether to show the task types next to their names in the output.
     * <p>
     * This property can be set via command-line option '--types'.
     *
     * @since 7.4
     */
    @Incubating
    @Console
    @Option(option = "types", description = "Show task class types")
    public Property<Boolean> getShowTypes() {
        return showTypes;
    }

    @TaskAction
    void generate() {
        reportGenerator()
                .generateReport(model.get().projects, projectModel -> projectModel.get().project,
                        projectModel -> {
                            render(projectModel.get());
                            logClickableOutputFileUrl();
                        });
    }

    private TaskReportModel computeTaskReportModel() {
        return new TaskReportModel(computeProjectModels());
    }

    private List<Try<ProjectReportModel>> computeProjectModels() {
        List<Try<ProjectReportModel>> result = new ArrayList<>();
        for (BuildProject project : new TreeSet<>(getProjects())) {
            result.add(Try.ofFailable(() -> projectReportModelFor(project)));
        }
        return result;
    }

    private static class TaskReportModel {
        final List<Try<ProjectReportModel>> projects;

        public TaskReportModel(List<Try<ProjectReportModel>> projects) {
            this.projects = projects;
        }
    }

    private static class ProjectReportModel {
        public final ProjectDetails project;
        public final List<String> defaultTasks;
        public final DefaultGroupTaskReportModel tasks;
        public final List<RuleDetails> rules;

        public ProjectReportModel(ProjectDetails project,
                                  List<String> defaultTasks,
                                  DefaultGroupTaskReportModel tasks,
                                  List<RuleDetails> rules) {
            this.project = project;
            this.defaultTasks = defaultTasks;
            this.tasks = tasks;
            this.rules = rules;
        }
    }

    private ProjectReportModel projectReportModelFor(BuildProject project) {
        return new ProjectReportModel(ProjectDetails.of(project), project.getDefaultTasks(),
                taskReportModelFor(project, isDetail()),
                Strings.isNullOrEmpty(group) ? ruleDetailsFor(project) : emptyList());
    }

    private void render(ProjectReportModel reportModel) {
        renderer.showDetail(isDetail());
        renderer.showTypes(getShowTypes().get());
        renderer.addDefaultTasks(reportModel.defaultTasks);

        DefaultGroupTaskReportModel model = reportModel.tasks;
        for (String group : model.getGroups()) {
            renderer.startTaskGroup(group);
            for (TaskDetails task : model.getTasksForGroup(group)) {
                renderer.addTask(task);
            }
        }
        renderer.completeTasks();

        for (RuleDetails rule : reportModel.rules) {
            renderer.addRule(rule);
        }
    }

    private List<RuleDetails> ruleDetailsFor(BuildProject project) {
        return project.getTasks().getRules().stream()
                .map(rule -> RuleDetails.of(rule.getDescription())).collect(Collectors.toList());
    }

    private DefaultGroupTaskReportModel taskReportModelFor(BuildProject project, boolean detail) {
        final AggregateMultiProjectTaskReportModel aggregateModel =
                new AggregateMultiProjectTaskReportModel(!detail, detail, getDisplayGroup());
        final TaskDetailsFactory taskDetailsFactory = new TaskDetailsFactory(project);

        final SingleProjectTaskReportModel projectTaskModel =
                buildTaskReportModelFor(taskDetailsFactory, project);
        aggregateModel.add(projectTaskModel);

        for (final BuildProject subproject : project.getSubprojects()) {
            aggregateModel.add(buildTaskReportModelFor(taskDetailsFactory, subproject));
        }

        aggregateModel.build();

        return DefaultGroupTaskReportModel.of(aggregateModel);
    }

    private SingleProjectTaskReportModel buildTaskReportModelFor(final TaskDetailsFactory taskDetailsFactory,
                                                                 final BuildProject subproject) {
        return projectStateFor(subproject).fromMutableState(project -> SingleProjectTaskReportModel
                .forTasks(getProjectTaskLister().listProjectTasks(project), taskDetailsFactory));
    }

    private ProjectStateUnk projectStateFor(BuildProject subproject) {
        return getProjectStateRegistry().stateFor(subproject);
    }

    /**
     * Injects a {@code ProjectStateRegistry} service.
     *
     * @since 5.0
     */
    @Inject
    protected ProjectStateRegistry getProjectStateRegistry() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected ProjectTaskLister getProjectTaskLister() {
        throw new UnsupportedOperationException();
    }
}
