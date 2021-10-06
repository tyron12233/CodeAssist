package com.tyron.code.ui.wizard;


import com.tyron.builder.parser.FileManager;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

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
            JSONObject jsonObject = new JSONObject(FileUtils.readFileToString(infoFile, Charset.defaultCharset()));
            template.setMinSdk(jsonObject.getInt("minSdk"));
            template.setName(jsonObject.getString("name"));
            template.setPath(parent.getAbsolutePath());

            return template;
        } catch (JSONException | IOException e) {
            return null;
        }
    }
    private String name;

    private int minSdk;

    private String path;

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
}
