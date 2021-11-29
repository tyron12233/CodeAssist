package com.tyron.builder.project.impl;

import com.tyron.builder.model.ProjectSettings;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.api.ModuleManager;
import com.tyron.common.util.StringSearch;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import kotlin.io.FileTreeWalk;
import kotlin.io.FileWalkDirection;
import kotlin.io.FilesKt;

public class JavaModuleManager implements ModuleManager<JavaModule> {

    private final JavaModule mModule;
    private final FileManager mFileManager;
    private final File mRoot;

    public JavaModuleManager(FileManager fileManager, File root) {
        mModule = new JavaModule();
        mFileManager = fileManager;
        mRoot = root;
    }

    @Override
    public void initialize() throws IOException {
        readPreferences();

        FileTreeWalk walk = FilesKt.walkTopDown(mModule.getData(JavaModule.JAVA_DIR_KEY));
        walk.iterator().forEachRemaining(file -> {
            String packageName = StringSearch.packageName(file);
            mModule.addJavaFile(packageName, file);
        });
    }

    private void readPreferences() {
        File prefFile = new File(mRoot, "build.json");
        if (prefFile.exists()) {
            // TODO: Parse json
        } else {
            putDefaultData();
        }
    }

    private void putDefaultData() {
        mModule.putData(JavaModule.JAVA_DIR_KEY, new File(mRoot, "src/main/java"));
    }

    @Override
    public JavaModule getModule() {
        return mModule;
    }

    @Override
    public void addDependingModule(Module module) {
        mModule.addDependingModule(module);
    }
}
