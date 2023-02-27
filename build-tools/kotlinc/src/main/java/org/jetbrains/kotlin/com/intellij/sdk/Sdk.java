package org.jetbrains.kotlin.com.intellij.sdk;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleImpl;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderRootType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootProvider;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.RootProviderBaseImpl;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Sdk extends RootProviderBaseImpl {

    private final String name;
    private final Project project;
    private final List<File> jarFiles;

    public Sdk(String name, Project project, String path, List<File> jarFiles) {
        this.name = name;
        this.project = project;
        this.jarFiles = jarFiles;
    }

    public String getName() {
        return name;
    }

    public Project getProject() {
        return project;
    }

    public List<File> getJarFiles() {
        return jarFiles;
    }

    @Override
    public String[] getUrls(@NonNull OrderRootType rootType) {
        return Arrays.stream(getFiles(rootType))
                .map(VirtualFile::getUrl)
                .toArray(String[]::new);
    }

    @Override
    public VirtualFile[] getFiles(@NonNull OrderRootType rootType) {
        VirtualFileSystem fs = StandardFileSystems.jar();
        return getJarFiles().stream()
                .map(it -> fs.findFileByPath(it.getPath() + "!/"))
                .toArray(VirtualFile[]::new);
    }

    public RootProvider getRootProvider() {
        return this;
    }
}
