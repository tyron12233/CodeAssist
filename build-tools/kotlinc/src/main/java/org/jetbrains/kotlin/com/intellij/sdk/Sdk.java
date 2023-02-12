package org.jetbrains.kotlin.com.intellij.sdk;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleImpl;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;

import java.io.File;
import java.util.List;

public class Sdk extends ModuleImpl {

    private final List<File> jarFiles;

    public Sdk(String name, Project project, String path, List<File> jarFiles) {
        super(name, project, path);
        this.jarFiles = jarFiles;
    }

    public List<File> getJarFiles() {
        return jarFiles;
    }
}
