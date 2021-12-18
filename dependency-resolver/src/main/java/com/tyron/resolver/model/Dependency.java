package com.tyron.resolver.model;

import androidx.annotation.NonNull;

public class Dependency {

    public Dependency() {

    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getVersionName() {
        if (versionName == null) {
            return "";
        }
        String temp = versionName.replace("[",  "")
                .replace("]", "")
                .replace("(", "")
                .replace(")", "");
        if (temp.contains(",")) {
            String[] versions = temp.split(",");
            for (String version : versions) {
                // return the first version for now.
                if (!version.isEmpty()) {
                    return version;
                }
            }
        }
        return temp;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    private String artifactId;
    private String groupId;
    private String versionName;
    private String scope;
    private String type;

    @NonNull
    @Override
    public String toString() {
        return getGroupId() + ":" + getArtifactId() + ":" + getVersionName();
    }

    public String getType() {
        return type;
    }
    public void setType(String type) {
        this.type = type;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }
}