package org.jetbrains.kotlin.com.intellij.openapi.module.java;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleImpl;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;

public class JavaModule extends ModuleImpl {

    public JavaModule(String name, Project project, String filePath) {
        super(name, project, filePath);
    }
}
