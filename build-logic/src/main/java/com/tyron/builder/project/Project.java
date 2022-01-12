package com.tyron.builder.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.eventbus.EventBus;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import com.tyron.builder.model.ProjectSettings;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.impl.AndroidModuleImpl;

import org.jetbrains.kotlin.com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBusFactory;
import org.jetbrains.kotlin.com.intellij.util.messages.SimpleMessageBusConnection;
import org.jetbrains.kotlin.com.intellij.util.messages.impl.MessageBusFactoryImpl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class Project {

    private final List<Module> mModules;
    private final Module mMainModule;
    private final File mRoot;

    private final ProjectSettings mSettings;
    
    public Project(File root) {
        mRoot = root;
        mModules = new ArrayList<>();
        mMainModule = new AndroidModuleImpl(new File(mRoot, "app"));
        mSettings = new ProjectSettings(new File(root, "settings.json"));
    }

    @NonNull
    public Module getMainModule() {
        return mMainModule;
    }

    public File getRootFile() {
        return mRoot;
    }

    public ProjectSettings getSettings() {
        return mSettings;
    }

    public Module getModule(File file) {
        return getMainModule();
    }

    public List<Module> getDependencies(Module module) {
        return Collections.emptyList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Project project = (Project) o;
        return mRoot.equals(project.mRoot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRoot);
    }

    @SuppressWarnings("UnstableApiUsage")
    public List<Module> getBuildOrder() throws IOException  {
        MutableGraph<Module> graph = GraphBuilder
                .directed()
                .allowsSelfLoops(false).build();
        graph.addNode(mMainModule);
        addEdges(graph, mMainModule);
        Set<Module> modules = Graphs.reachableNodes(graph, mMainModule);
        ArrayList<Module> list = new ArrayList<>(modules);
        Collections.reverse(list);
        return list;
    }

    @SuppressWarnings("UnstableApiUsage")
    private void addEdges(MutableGraph<Module> graph, Module module) throws IOException {
        Set<String> modules = module.getSettings().getStringSet("modules",
                Collections.emptySet());
        if (modules == null) {
            return;
        }

        for (String s : modules) {
            File moduleRoot = new File(mRoot, s);
            if (!moduleRoot.exists()) {
                continue;
            }
            Module subModule = ModuleUtil.fromDirectory(moduleRoot);
            if (subModule != null) {
                subModule.open();
                graph.putEdge(module, subModule);

                addEdges(graph, subModule);
            }
        }
    }

}
