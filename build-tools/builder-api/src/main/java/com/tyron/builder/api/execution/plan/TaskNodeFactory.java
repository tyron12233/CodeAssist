package com.tyron.builder.api.execution.plan;

import com.google.common.collect.Maps;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.execution.WorkValidationContext;
import com.tyron.builder.api.internal.execution.impl.DefaultWorkValidationContext;
import com.tyron.builder.api.project.BuildProject;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class TaskNodeFactory {
    private final Map<Task, TaskNode> nodes = new HashMap<>();
//    private final BuildTreeWorkGraphController workGraphController;
    private final NodeValidator nodeValidator;
//    private final GradleInternal thisBuild;
//    private final DocumentationRegistry documentationRegistry;
    private final DefaultTypeOriginInspectorFactory typeOriginInspectorFactory;
//
//    public TaskNodeFactory(
//            GradleInternal thisBuild,
//            DocumentationRegistry documentationRegistry,
//            BuildTreeWorkGraphController workGraphController,
//            NodeValidator nodeValidator
//    ) {
//        this.thisBuild = thisBuild;
//        this.documentationRegistry = documentationRegistry;
//        this.workGraphController = workGraphController;
//        this.nodeValidator = nodeValidator;
//        this.typeOriginInspectorFactory = new DefaultTypeOriginInspectorFactory();
//    }

    public TaskNodeFactory(
            NodeValidator validator
    ) {
        nodeValidator = validator;
        typeOriginInspectorFactory = new DefaultTypeOriginInspectorFactory();
    }

    public Set<Task> getTasks() {
        return nodes.keySet();
    }

    @Nullable
    public TaskNode getNode(Task task) {
        return nodes.get(task);
    }

    public TaskNode getOrCreateNode(Task task) {
        TaskNode node = nodes.get(task);
        if (node == null) {
//            if (task.getProject().getGradle() == thisBuild) {
                node = new LocalTaskNode((TaskInternal) task, nodeValidator, new DefaultWorkValidationContext(typeOriginInspectorFactory.forTask(task)));
//            } else {
//                node = TaskInAnotherBuild.of((TaskInternal) task, workGraphController);
//            }
            nodes.put(task, node);
        }
        return node;
    }

    public void clear() {
        nodes.clear();
    }

    private static class DefaultTypeOriginInspectorFactory {
        private final Map<BuildProject, ProjectScopedTypeOriginInspector> projectToInspector = Maps.newConcurrentMap();
        private final Map<Class<?>, File> clazzToFile = Maps.newConcurrentMap();

        public ProjectScopedTypeOriginInspector forTask(Task task) {
            return projectToInspector.computeIfAbsent(task.getProject(), ProjectScopedTypeOriginInspector::new);
        }

        @Nullable
        private File jarFileFor(Class<?> pluginClass) {
            return clazzToFile.computeIfAbsent(pluginClass, clazz -> toFile(pluginClass.getProtectionDomain().getCodeSource().getLocation()));
        }

        @Nullable
        private static File toFile(@Nullable URL url) {
            if (url == null) {
                return null;
            }
            try {
                return new File(url.toURI());
            } catch (URISyntaxException e) {
                return null;
            }
        }

        private class ProjectScopedTypeOriginInspector implements WorkValidationContext.TypeOriginInspector {
//            private final PluginContainer plugins;
//            private final PluginManagerInternal pluginManager;
            private final Map<Class<?>, Optional<String>> classToPlugin = Maps.newConcurrentMap();

            private ProjectScopedTypeOriginInspector(BuildProject project) {
//                this.plugins = project.getPlugins();
//                this.pluginManager = (PluginManagerInternal) project.getPluginManager();
            }

            @Override
            public Optional<String> findPluginDefining(Class<?> type) {
                return Optional.empty();
//                return classToPlugin.computeIfAbsent(type, clazz -> {
//                    File taskJar = jarFileFor(type);
//                    return plugins.stream()
//                            .map(plugin -> Cast.<Class<Plugin<?>>>uncheckedNonnullCast(plugin.getClass()))
//                            .filter(pluginType -> Objects.equals(jarFileFor(pluginType), taskJar))
//                            .map(pluginType -> pluginManager.findPluginIdForClass(pluginType)
//                                    .orElseGet(() -> new DefaultPluginId(pluginType.getName())))
//                            .findFirst();
//                });
            }
        }
    }
}
