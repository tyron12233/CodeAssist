package com.tyron.builder.project.impl;

import com.tyron.builder.compiler.manifest.xml.ManifestData;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

import java.io.File;

public class AndroidModule extends JavaModule {

    public static final Key<File> MANIFEST_FILE_KEY = Key.create("manifestFile");
    public static final Key<ManifestData> MANIFEST_DATA_KEY = Key.create("manifestData");

    public ManifestData getManifestData() {
        return getData(MANIFEST_DATA_KEY);
    }
}
