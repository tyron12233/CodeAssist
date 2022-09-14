package com.tyron.builder.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import com.tyron.builder.model.ProjectSettings;
import com.tyron.builder.project.api.ContentRoot;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.impl.AndroidModuleImpl;
import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.code.event.EventManager;

import org.jetbrains.kotlin.com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBusFactory;
import org.jetbrains.kotlin.com.intellij.util.messages.SimpleMessageBusConnection;
import org.jetbrains.kotlin.com.intellij.util.messages.impl.MessageBusFactoryImpl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

@SuppressWarnings("UnstableApiUsage")
public class Project {

    private final Module EMPTY = new MockAndroidModule(null, null);

    private final Map<String, Module> mModules;
    private final File mRoot;

    private final ProjectSettings mSettings;

    private volatile boolean mCompiling;
    private volatile boolean mIndexing;

    private final EventManager eventManager;

    public Project(File root) {
        mRoot = root;
        mModules = new LinkedHashMap<>();
        mSettings = new ProjectSettings(new File(root, "settings.json"));
        eventManager = new EventManager();
    }

    public void clear() {
        mModules.clear();
    }

    public void addModule(Module module) {
        assert module.getProject() == null;
        module.setProject(this);

        mModules.put(module.getName(), module);
    }

    public boolean isCompiling() {
        return mCompiling;
    }

    public void setCompiling(boolean compiling) {
        mCompiling = compiling;
    }

    public void setIndexing(boolean indexing) {
        mIndexing = indexing;
    }

    public boolean isIndexing() {
        return mIndexing;
    }

    public void open() throws IOException {
    }

    public void index() throws IOException {

    }

    /**
     * @return All the modules from the main module, order is not guaranteed
     */
    public Collection<Module> getModules() {
        return mModules.values();
    }

    @NonNull
    public Module getMainModule() {
        if (mModules.isEmpty()) {
            return EMPTY;
        }
        return mModules.values().iterator().next();
    }

    public File getRootFile() {
        return mRoot;
    }

    public ProjectSettings getSettings() {
        return mSettings;
    }

    public Module getModule(File file) {
        for (Module value : mModules.values()) {
            for (ContentRoot contentRoot : value.getContentRoots()) {
                for (File sourceDirectory : contentRoot.getSourceDirectories()) {
                    if (directoryContainsFile(sourceDirectory, file)) {
                        return value;
                    }
                }
            }
        }
        return getMainModule();
    }

    public Module getModuleByName(String name) {
        return mModules.get(name);
    }

    private boolean directoryContainsFile(File dir, File file) {
        try {
            File rootFile = dir.getCanonicalFile();
            File absoluteFile = file.getCanonicalFile();

            return absoluteFile.exists() && absoluteFile.getAbsolutePath().startsWith(rootFile.getAbsolutePath());
        } catch (IOException e) {
            return false;
        }
    }

    public List<Module> getDependencies(Module module) {
        return ImmutableList.copyOf(mModules.values())
                .reverse();
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

    public EventManager getEventManager() {
        return eventManager;
    }
}
