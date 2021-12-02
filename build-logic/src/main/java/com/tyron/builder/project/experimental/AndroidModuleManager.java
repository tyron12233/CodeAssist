package com.tyron.builder.project.experimental;

import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;

import java.io.File;
import java.io.IOException;

public class AndroidModuleManager extends JavaModuleManager {

    private final AndroidModule mModule;
    private final File mRoot;

    public AndroidModuleManager(FileManager fileManager, File root) {
        super(fileManager, root);
        mModule = new AndroidModule();
        mRoot = root;
    }

    @Override
    public void initialize() throws IOException {
        super.initialize();
        putDefaultData();

        mModule.putData(AndroidModule.MANIFEST_DATA_KEY,
                AndroidManifestParser.parse(mModule.getData(AndroidModule.MANIFEST_FILE_KEY)));
    }

    private void putDefaultData() {
        mModule.putData(AndroidModule.MANIFEST_FILE_KEY,
                new File(mRoot, "src/main/AndroidManifest.xml"));
    }

    @Override
    public void addDependingModule(Module module) {
        mModule.addDependingModule(module);
    }

    @Override
    public AndroidModule getModule() {
        return mModule;
    }
}
