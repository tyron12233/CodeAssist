package com.tyron.builder.project;

import com.tyron.builder.parser.ModuleParser;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.api.ModuleManager;
import com.tyron.builder.project.impl.AndroidModuleManager;
import com.tyron.builder.project.impl.FileManagerImpl;
import com.tyron.builder.project.impl.ProjectImpl;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AndroidProjectManager {

    private final File mRoot;
    private final FileManager mFileManager;
    private final ProjectImpl mProject;
    private final Map<String, ModuleManager<?>> mModuleManagers;

    public AndroidProjectManager(File root) {
        mRoot = root;
        mFileManager = new FileManagerImpl();
        mProject = new ProjectImpl(root);
        mModuleManagers = new HashMap<>();
    }

    public void initialize() throws IOException, JSONException {
        List<File> roots = parseModuleRoots();
        mModuleManagers.putAll(getModuleManagerFromRoots(roots));
        for (ModuleManager<?> mModuleManager : mModuleManagers.values()) {
            mModuleManager.initialize();
        }
    }

    public List<Module> getModules() {
        return mModuleManagers.values().stream()
                .map(ModuleManager::getModule)
                .collect(Collectors.toList());
    }

    private Map<String, ModuleManager<?>> getModuleManagerFromRoots(List<File> roots) {
        Map<String, ModuleManager<?>> moduleManagers = new HashMap<>();
        for (File root : roots) {
            if (!root.exists()) {
                continue;
            }

            try {
                String type = new ModuleParser(root).parse();

                if ("AndroidModule".equals(type)) {
                    moduleManagers.put(root.getName(), new AndroidModuleManager(mFileManager, root));
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        }

        return moduleManagers;
    }

    private List<File> parseModuleRoots() throws JSONException, IOException {
        List<File> moduleRoots = new ArrayList<>();
        String json = FileUtils.readFileToString(
                new File(mRoot, "modules.json"), StandardCharsets.UTF_8);
        JSONObject jsonObject = new JSONObject(json);
        JSONArray modules = jsonObject.getJSONArray("modules");
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.getJSONObject(i);
            String path = module.getString("path");
            moduleRoots.add(new File(mRoot, path));
        }
        return moduleRoots;
    }

    private void calculateDependencies() {

    }
}
