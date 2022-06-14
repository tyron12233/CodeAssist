/*
 * Copyright 2014 the original author or authors.
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
package com.tyron.builder.language.base.plugins;

import static com.google.common.base.Strings.emptyToNull;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.project.ProjectIdentifier;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.plugins.ExtensionContainer;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.internal.logging.text.TreeFormatter;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.language.base.LanguageSourceSet;
import com.tyron.builder.language.base.ProjectSourceSet;
import com.tyron.builder.language.base.internal.ProjectLayout;
import com.tyron.builder.language.base.internal.model.BinarySourceTransformations;
import com.tyron.builder.language.base.internal.registry.DefaultLanguageTransformContainer;
import com.tyron.builder.language.base.internal.registry.LanguageTransform;
import com.tyron.builder.language.base.internal.registry.LanguageTransformContainer;
import com.tyron.builder.model.Defaults;
import com.tyron.builder.model.Each;
import com.tyron.builder.model.Finalize;
import com.tyron.builder.model.Model;
import com.tyron.builder.model.ModelMap;
import com.tyron.builder.model.Mutate;
import com.tyron.builder.model.Path;
import com.tyron.builder.model.RuleInput;
import com.tyron.builder.model.RuleSource;
import com.tyron.builder.model.RuleTarget;
import com.tyron.builder.model.Rules;
import com.tyron.builder.model.internal.core.Hidden;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;
import com.tyron.builder.platform.base.ApplicationSpec;
import com.tyron.builder.platform.base.BinaryContainer;
import com.tyron.builder.platform.base.BinarySpec;
import com.tyron.builder.platform.base.ComponentSpecContainer;
import com.tyron.builder.platform.base.ComponentType;
import com.tyron.builder.platform.base.GeneralComponentSpec;
import com.tyron.builder.platform.base.LibrarySpec;
import com.tyron.builder.platform.base.PlatformAwareComponentSpec;
import com.tyron.builder.platform.base.PlatformContainer;
import com.tyron.builder.platform.base.SourceComponentSpec;
import com.tyron.builder.platform.base.TypeBuilder;
import com.tyron.builder.platform.base.VariantComponentSpec;
import com.tyron.builder.platform.base.component.BaseComponentSpec;
import com.tyron.builder.platform.base.internal.BinarySpecInternal;
import com.tyron.builder.platform.base.internal.DefaultPlatformContainer;
import com.tyron.builder.platform.base.internal.DefaultPlatformResolvers;
import com.tyron.builder.platform.base.internal.HasIntermediateOutputsComponentSpec;
import com.tyron.builder.platform.base.internal.PlatformAwareComponentSpecInternal;
import com.tyron.builder.platform.base.internal.PlatformResolvers;
import com.tyron.builder.platform.base.internal.dependents.BaseDependentBinariesResolutionStrategy;
import com.tyron.builder.platform.base.internal.dependents.DefaultDependentBinariesResolver;
import com.tyron.builder.platform.base.internal.dependents.DependentBinariesResolver;
import com.tyron.builder.platform.base.plugins.BinaryBasePlugin;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Base plugin for component support.
 * <p>
 * Adds a {@link com.tyron.builder.platform.base.ComponentSpecContainer} named {@code components}
 * to the model.
 * <p>
 * For each binary instance added to the binaries container, registers a lifecycle task to create
 * that binary.
 */
@Incubating
public class ComponentModelBasePlugin implements Plugin<BuildProject> {

    @Override
    public void apply(BuildProject project) {
        project.getPluginManager().apply(LanguageBasePlugin.class);
        project.getPluginManager().apply(BinaryBasePlugin.class);
    }

    @SuppressWarnings("UnusedDeclaration")
    static class PluginRules extends RuleSource {
        @ComponentType
        void registerGeneralComponentSpec(TypeBuilder<GeneralComponentSpec> builder) {
            builder.defaultImplementation(BaseComponentSpec.class);
        }

        @ComponentType
        void registerLibrarySpec(TypeBuilder<LibrarySpec> builder) {
            builder.defaultImplementation(BaseComponentSpec.class);
        }

        @ComponentType
        void registerApplicationSpec(TypeBuilder<ApplicationSpec> builder) {
            builder.defaultImplementation(BaseComponentSpec.class);
        }

        @ComponentType
        void registerPlatformAwareComponent(TypeBuilder<PlatformAwareComponentSpec> builder) {
            builder.internalView(PlatformAwareComponentSpecInternal.class);
        }

        @Hidden
        @Model
        LanguageTransformContainer languageTransforms(CollectionCallbackActionDecorator collectionCallbackActionDecorator) {
            return new DefaultLanguageTransformContainer(collectionCallbackActionDecorator);
        }

        // Finalizing here, as we need this to run after any 'assembling' task (jar, link, etc)
        // is created.
        // TODO: Convert this to `@BinaryTasks` when we model a `NativeAssembly` instead of
        //  wiring compile tasks directly to LinkTask
        @Finalize
        void createSourceTransformTasks(final TaskContainer tasks,
                                        @Path("binaries") final ModelMap<BinarySpecInternal> binaries,
                                        LanguageTransformContainer languageTransforms,
                                        ServiceRegistry serviceRegistry) {
            BinarySourceTransformations transformations =
                    new BinarySourceTransformations(tasks, languageTransforms, serviceRegistry);
            for (BinarySpecInternal binary : binaries) {
                if (binary.isLegacyBinary()) {
                    continue;
                }

                transformations.createTasksFor(binary);
            }
        }

        @Model
        public ProjectLayout projectLayout(ProjectIdentifier projectIdentifier,
                                           @Path("buildDir") File buildDir) {
            return new ProjectLayout(projectIdentifier, buildDir);
        }

        @Model
        PlatformContainer platforms(Instantiator instantiator,
                                    CollectionCallbackActionDecorator collectionCallbackActionDecorator) {
            return instantiator.newInstance(DefaultPlatformContainer.class, instantiator,
                    collectionCallbackActionDecorator);
        }

        @Hidden
        @Model
        PlatformResolvers platformResolver(PlatformContainer platforms) {
            return new DefaultPlatformResolvers(platforms);
        }

        @Mutate
        void registerPlatformExtension(ExtensionContainer extensions, PlatformContainer platforms) {
            extensions.add(PlatformContainer.class, "platforms", platforms);
        }

        @Mutate
        void collectBinaries(BinaryContainer binaries, ComponentSpecContainer componentSpecs) {
            for (VariantComponentSpec componentSpec : componentSpecs
                    .withType(VariantComponentSpec.class)) {
                for (BinarySpecInternal binary : componentSpec.getBinaries()
                        .withType(BinarySpecInternal.class).values()) {
                    binaries.put(binary.getProjectScopedName(), binary);
                }
            }
        }

        @Mutate
        void attachBinariesToAssembleLifecycle(@Path("tasks.assemble") Task assemble,
                                               ComponentSpecContainer components) {
            List<BinarySpecInternal> notBuildable = Lists.newArrayList();
            boolean hasBuildableBinaries = false;
            for (VariantComponentSpec component : components.withType(VariantComponentSpec.class)) {
                for (BinarySpecInternal binary : component.getBinaries()
                        .withType(BinarySpecInternal.class)) {
                    if (binary.isBuildable()) {
                        assemble.dependsOn(binary);
                        hasBuildableBinaries = true;
                    } else {
                        notBuildable.add(binary);
                    }
                }
            }
            if (!hasBuildableBinaries && !notBuildable.isEmpty()) {
                assemble.doFirst(new CheckForNotBuildableBinariesAction(notBuildable));
            }
        }

        private static class CheckForNotBuildableBinariesAction implements Action<Task> {
            private final List<BinarySpecInternal> notBuildable;

            public CheckForNotBuildableBinariesAction(List<BinarySpecInternal> notBuildable) {
                this.notBuildable = notBuildable;
            }

            @Override
            public void execute(Task task) {
                Set<? extends Task> taskDependencies =
                        task.getTaskDependencies().getDependencies(task);

                if (taskDependencies.isEmpty()) {
                    TreeFormatter formatter = new TreeFormatter();
                    formatter.node("No buildable binaries found");
                    formatter.startChildren();
                    for (BinarySpecInternal binary : notBuildable) {
                        formatter.node(binary.getDisplayName());
                        formatter.startChildren();
                        binary.getBuildAbility().explain(formatter);
                        formatter.endChildren();
                    }
                    formatter.endChildren();
                    throw new BuildException(formatter.toString());
                }
            }
        }

        @Defaults
        void initializeComponentSourceSets(@Each HasIntermediateOutputsComponentSpec component,
                                           LanguageTransformContainer languageTransforms) {
            // If there is a transform for the language into one of the component inputs, add a
            // default source set
            for (LanguageTransform<?, ?> languageTransform : languageTransforms) {
                if (component.getIntermediateTypes().contains(languageTransform.getOutputType())) {
                    component.getSources().create(languageTransform.getLanguageName(),
                            languageTransform.getSourceSetType());
                }
            }
        }

        @Finalize
        void applyFallbackSourceConventions(@Each LanguageSourceSet languageSourceSet,
                                            ProjectIdentifier projectIdentifier) {
            // Only apply default locations when none explicitly configured
            if (languageSourceSet.getSource().getSourceDirectories().isEmpty()) {
                File baseDir = projectIdentifier.getProjectDir();
                String defaultSourceDir = Joiner.on(File.separator).skipNulls()
                        .join(baseDir.getPath(), "src",
                                emptyToNull(languageSourceSet.getParentName()),
                                emptyToNull(languageSourceSet.getName()));
                languageSourceSet.getSource().srcDir(defaultSourceDir);
            }
        }

        @Finalize
        public void defineBinariesCheckTasks(@Each BinarySpecInternal binary,
                                             NamedEntityInstantiator<Task> taskInstantiator) {
            if (binary.isLegacyBinary()) {
                return;
            }
            TaskInternal binaryLifecycleTask = taskInstantiator
                    .create(binary.getNamingScheme().getTaskName("check"), DefaultTask.class);
            binaryLifecycleTask.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
            binaryLifecycleTask.setDescription("Check " + binary);
            binary.setCheckTask(binaryLifecycleTask);
        }

        @Finalize
        void copyBinariesCheckTasksToTaskContainer(TaskContainer tasks, BinaryContainer binaries) {
            for (BinarySpec binary : binaries) {
                Task checkTask = binary.getCheckTask();
                if (checkTask != null) {
                    ((TaskContainerInternal) tasks).addInternal(checkTask);
                }
            }
        }

        @Defaults
            // TODO:LPTR We should collect all source sets in the project source set, however
            //  this messes up ComponentReportRenderer
        void addComponentSourcesSetsToProjectSourceSet(@Each SourceComponentSpec component,
                                                       final ProjectSourceSet projectSourceSet) {
            component.getSources().afterEach(new Action<LanguageSourceSet>() {
                @Override
                public void execute(LanguageSourceSet languageSourceSet) {
                    projectSourceSet.add(languageSourceSet);
                }
            });
        }

        @Rules
        void inputRules(AttachInputs attachInputs, @Each GeneralComponentSpec component) {
            attachInputs.setBinaries(component.getBinaries());
            attachInputs.setSources(component.getSources());
        }

        static abstract class AttachInputs extends RuleSource {
            @RuleTarget
            abstract ModelMap<BinarySpec> getBinaries();

            abstract void setBinaries(ModelMap<BinarySpec> binaries);

            @RuleInput
            abstract ModelMap<LanguageSourceSet> getSources();

            abstract void setSources(ModelMap<LanguageSourceSet> sources);

            @Mutate
            void initializeBinarySourceSets(ModelMap<BinarySpec> binaries) {
                // TODO - sources is not actual an input to binaries, it's an input to each binary
                binaries.withType(BinarySpecInternal.class, new Action<BinarySpecInternal>() {
                    @Override
                    public void execute(BinarySpecInternal binary) {
                        binary.getInputs().addAll(getSources().values());
                    }
                });
            }
        }

        @Hidden
        @Model
        DependentBinariesResolver dependentBinariesResolver(Instantiator instantiator) {
            return instantiator.newInstance(DefaultDependentBinariesResolver.class);
        }

        @Defaults
        void registerBaseDependentBinariesResolutionStrategy(DependentBinariesResolver resolver,
                                                             ServiceRegistry serviceRegistry) {
            resolver.register(new BaseDependentBinariesResolutionStrategy());
        }
    }
}
