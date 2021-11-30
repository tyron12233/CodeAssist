package com.tyron.builder.project.impl;

import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.api.ModuleManager;
import com.tyron.common.util.StringSearch;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import kotlin.io.FileTreeWalk;
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
        parseModuleFile();

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

    private void parseModuleFile() throws IOException {
        File moduleFile = new File(mRoot, "module.json");
        String contents = FileUtils.readFileToString(moduleFile, StandardCharsets.UTF_8);
        try {
            JSONObject jsonObject = new JSONObject(contents);
            JSONArray dependencies = jsonObject.getJSONArray("dependencies");
            for (int i = 0; i < dependencies.length(); i++) {
                JSONObject object = dependencies.getJSONObject(i);
                String path = object.getString("path");
                File newRoot = new File(mRoot.getParentFile(), path);
                AndroidModuleManager androidModuleManager = new AndroidModuleManager(mFileManager, newRoot);
                androidModuleManager.initialize();
                addDependingModule(androidModuleManager.getModule());
            }
        } catch (JSONException e) {
            // dependencies are not added if json file is malformed
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
