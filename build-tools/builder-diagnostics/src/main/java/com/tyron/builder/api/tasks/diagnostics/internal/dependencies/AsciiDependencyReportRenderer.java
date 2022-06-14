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
package com.tyron.builder.api.tasks.diagnostics.internal.dependencies;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.result.ResolutionResult;
import com.tyron.builder.api.tasks.diagnostics.internal.DependencyReportRenderer;
import com.tyron.builder.api.tasks.diagnostics.internal.ProjectDetails;
import com.tyron.builder.api.tasks.diagnostics.internal.TextReportRenderer;
import com.tyron.builder.api.tasks.diagnostics.internal.graph.DependencyGraphsRenderer;
import com.tyron.builder.api.tasks.diagnostics.internal.graph.NodeRenderer;
import com.tyron.builder.api.tasks.diagnostics.internal.graph.SimpleNodeRenderer;
import com.tyron.builder.api.tasks.diagnostics.internal.graph.nodes.RenderableDependency;
import com.tyron.builder.api.tasks.diagnostics.internal.graph.nodes.RenderableModuleResult;
import com.tyron.builder.api.tasks.diagnostics.internal.graph.nodes.UnresolvableConfigurationResult;
import com.tyron.builder.internal.deprecation.DeprecatableConfiguration;
import com.tyron.builder.internal.graph.GraphRenderer;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.util.internal.GUtil;

import java.util.Collections;

import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.Description;
import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.Identifier;
import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.Info;
import static com.tyron.builder.internal.logging.text.StyledTextOutput.Style.UserInput;

/**
 * Simple dependency graph renderer that emits an ASCII tree.
 */
@NonNullApi
public class AsciiDependencyReportRenderer extends TextReportRenderer implements DependencyReportRenderer {
    private final ConfigurationAction configurationAction = new ConfigurationAction();
    private boolean hasConfigs;
    private GraphRenderer renderer;

    DependencyGraphsRenderer dependencyGraphRenderer;

    @Override
    public void startProject(ProjectDetails project) {
        super.startProject(project);
        prepareVisit();
    }

    void prepareVisit() {
        hasConfigs = false;
        renderer = new GraphRenderer(getTextOutput());
        dependencyGraphRenderer = new DependencyGraphsRenderer(getTextOutput(), renderer, NodeRenderer.NO_OP, new SimpleNodeRenderer());
    }

    @Override
    public void completeProject(ProjectDetails project) {
        if (!hasConfigs) {
            getTextOutput().withStyle(Info).println("No configurations");
        }
        super.completeProject(project);
    }

    @Override
    public void startConfiguration(final Configuration configuration) {
        if (hasConfigs) {
            getTextOutput().println();
        }
        hasConfigs = true;
        configurationAction.setConfiguration(configuration);
        renderer.visit(configurationAction, true);

    }

    private String getDescription(Configuration configuration) {
        return GUtil.isTrue(configuration.getDescription()) ? " - " + configuration.getDescription() : "";
    }

    @Override
    public void completeConfiguration(Configuration configuration) {}

    @Override
    public void render(Configuration configuration) {
        if (canBeResolved(configuration)) {
            ResolutionResult result = configuration.getIncoming().getResolutionResult();
            RenderableDependency root = new RenderableModuleResult(result.getRoot());
            renderNow(root);
        } else {
            renderNow(new UnresolvableConfigurationResult(configuration));
        }
    }

    private boolean canBeResolved(Configuration configuration) {
        boolean isDeprecatedForResolving = ((DeprecatableConfiguration) configuration).getResolutionAlternatives() != null;
        return configuration.isCanBeResolved() && !isDeprecatedForResolving;
    }

    void renderNow(RenderableDependency root) {
        if (root.getChildren().isEmpty()) {
            getTextOutput().withStyle(Info).text("No dependencies");
            getTextOutput().println();
            return;
        }

        dependencyGraphRenderer.render(Collections.singletonList(root));
    }

    @Override
    public void complete() {
        if (dependencyGraphRenderer != null) {
            dependencyGraphRenderer.complete();
        }

//        getTextOutput().println();
//        getTextOutput().text("A web-based, searchable dependency report is available by adding the ");
//        getTextOutput().withStyle(UserInput).format("--%s", StartParameterBuildOptions.BuildScanOption.LONG_OPTION);
//        getTextOutput().println(" option.");

        super.complete();
    }

    private class ConfigurationAction implements Action<StyledTextOutput> {
        private Configuration configuration;

        @Override
        public void execute(StyledTextOutput styledTextOutput) {
            getTextOutput().withStyle(Identifier).text(configuration.getName());
            getTextOutput().withStyle(Description).text(getDescription(configuration));
            if (!canBeResolved(configuration)) {
                getTextOutput().withStyle(Info).text(" (n)");
            }
        }

        public void setConfiguration(Configuration configuration) {
            this.configuration = configuration;
        }
    }
}
