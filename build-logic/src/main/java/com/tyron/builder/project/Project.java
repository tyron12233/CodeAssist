package com.tyron.builder.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.eventbus.EventBus;
import com.tyron.builder.model.ProjectSettings;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.impl.AndroidModuleImpl;

import org.jetbrains.kotlin.com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBusFactory;
import org.jetbrains.kotlin.com.intellij.util.messages.SimpleMessageBusConnection;
import org.jetbrains.kotlin.com.intellij.util.messages.impl.MessageBusFactoryImpl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

}
