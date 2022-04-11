package com.tyron.code.ui.wizard;


import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class WizardTemplate {

    public static WizardTemplate fromFile(File parent) {
        if (!parent.exists()) {
            return null;
        }

        if (!parent.isDirectory()) {
            return null;
        }

        File infoFile = new File(parent, "info.json");
        if (!infoFile.exists()) {
            return null;
        }

        WizardTemplate template = new WizardTemplate();
        try {
            JSONObject jsonObject = new JSONObject(FileUtils.readFileToString(infoFile,
                    StandardCharsets.UTF_8));
            template.setMinSdk(jsonObject.getInt("minSdk"));
            template.setName(jsonObject.getString("name"));
            template.setPath(parent.getAbsolutePath());
            template.setSupportsJava(jsonObject.getBoolean("supportsJava"));
            template.setSupportsKotlin(jsonObject.getBoolean("supportsKotlin"));
            return template;
        } catch (JSONException | IOException e) {
            return null;
        }
    }
    private String name;

    private int minSdk;

    private String path;

    private boolean supportsKotlin;

    private boolean supportsJava;

    public WizardTemplate() {

    }

    public int getMinSdk() {
        return minSdk;
    }

    public void setMinSdk(int minSdk) {
        this.minSdk = minSdk;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isSupportsJava() {
        return supportsJava;
    }

    public void setSupportsJava(boolean supportsJava) {
        this.supportsJava = supportsJava;
    }

    public boolean isSupportsKotlin() {
        return supportsKotlin;
    }

    public void setSupportsKotlin(boolean supportsKotlin) {
        this.supportsKotlin = supportsKotlin;
    }
}
