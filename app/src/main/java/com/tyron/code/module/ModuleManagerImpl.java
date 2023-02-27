package com.tyron.code.module;

import static org.jetbrains.kotlin.com.intellij.openapi.roots.impl.ModuleRootManagerImpl.JSON_OBJECT_KEY;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.graph.ElementOrder;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import com.tyron.code.ApplicationLoader;

import org.apache.commons.vfs2.provider.local.LocalFileSystem;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleImpl;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.module.java.JavaModule;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.ModuleRootManagerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import org.jetbrains.kotlin.com.intellij.util.graph.Graph;
import org.jetbrains.kotlin.com.intellij.util.graph.GraphGenerator;
import org.jetbrains.kotlin.com.intellij.util.graph.InboundSemiGraph;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ModuleManagerImpl extends ModuleManager {

    public static final String JAVA_MODULE = "JAVA_MODULE";
    private final Project project;
    private final MutableGraph<Module> graph =
            GraphBuilder.directed().allowsSelfLoops(false).build();

    public ModuleManagerImpl(Project project) {
        this.project = project;

    }

    public MutableGraph<Module> parse() throws IOException {
        parseProjectInfo(Objects.requireNonNull(project.getProjectFile()));
        return graph;
    }

    private void parseProjectInfo(@NonNull VirtualFile projectFile) throws IOException {
        VirtualFile projectInfo = projectFile.findChild("projectInfo.json");
        if (projectInfo == null || !projectInfo.exists()) {
            throw new IOException("projectInfo.json not found in project folder");
        }

        String projectInfoContent = new String(projectInfo.contentsToByteArray());
        try {
            JSONObject jsonObject = new JSONObject(projectInfoContent);
            JSONArray modules = jsonObject.getJSONArray("modules");

            for (int i = 0; i < modules.length(); i++) {
                String moduleName = modules.getString(i);

                VirtualFile child = projectFile.findChild(moduleName);
                if (child != null && child.exists()) {
                    loadModule(child.getPath());
                }
            }
        } catch (JSONException e) {
            throw new IOException("Failed to parse projectInfo " +
                                  projectInfo +
                                  "\n" +
                                  "content: " +
                                  projectInfoContent +
                                  "\n" +
                                  "exception: " +
                                  e);
        }
    }

    private Module parseModule(@NonNull VirtualFile moduleRoot) throws IOException {
        String moduleName = moduleRoot.getName();

        Optional<Module> existingModule =
                graph.nodes().stream().filter(it -> moduleName.equals(it.getName())).findAny();
        if (existingModule.isPresent()) {
            return existingModule.get();
        }

        ModuleImpl module = new ModuleImpl(moduleName, project, moduleRoot.getPath());
        graph.addNode(module);

        VirtualFile moduleInfoFile = moduleRoot.findChild("moduleInfo.json");
        if (moduleInfoFile == null || !moduleInfoFile.exists()) {
            throw new IOException("moduleInfo.json not found in module " + moduleName);
        }

        String moduleInfoContent = new String(moduleInfoFile.contentsToByteArray());
        try {
            JSONObject jsonObject = new JSONObject(moduleInfoContent);

            module.putUserData(JSON_OBJECT_KEY, jsonObject);

            JSONArray dependencies = jsonObject.getJSONArray("dependencies");
            for (int i = 0; i < dependencies.length(); i++) {
                String dependencyModuleName = dependencies.getString(i);
                VirtualFile child = moduleRoot.findChild(dependencyModuleName);
                if (child != null && child.exists()) {
                    Module dependencyModule = parseModule(child);
                    if (dependencyModule != null) {
                        graph.putEdge(module, dependencyModule);
                    }
                }
            }
        } catch (JSONException e) {
            throw new IOException("Failed to parse module " +
                                  moduleName +
                                  "\n" +
                                  "module content: " +
                                  moduleInfoContent +
                                  "\n" +
                                  "exception: " +
                                  e);
        }

        return module;
    }

    @Override
    public Module newModule(String filePath, String moduleTypeId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Module loadModule(String file) throws IOException {
        VirtualFile fileByPath = StandardFileSystems.local().findFileByPath(file);
        if (fileByPath == null) {
            throw new FileNotFoundException(file);
        }
        Module module = parseModule(fileByPath);
        ((ModuleImpl) module).registerService(ModuleRootManager.class,
                new ModuleRootManagerImpl(module));
        return module;
    }

    @Override
    public void disposeModule(Module module) {
        module.dispose();
    }

    @Override
    public Module[] getModules() {
        return graph.nodes().toArray(new Module[0]);
    }

    @Nullable
    @Override
    public Module findModuleByName(String name) {
        return graph.nodes()
                .stream()
                .filter(it -> name.equals(it.getName()))
                .findAny()
                .orElse(null);
    }

    @Override
    public Module[] getSortedModules() {
        return graph.nodes().toArray(new Module[0]);
    }

    @Override
    public Comparator<Module> getModuleDependencyComparator() {
        return null;
    }

    @Override
    public List<Module> getModuleDependentModules(Module module) {
        return new ArrayList<>(graph.adjacentNodes(module));
    }

    @Override
    public boolean isModuleDependent(Module module) {
        return false;
    }

    @Override
    public Graph<Module> getModuleGraph(boolean includeTests) {
        throw new UnsupportedOperationException();
    }
}
