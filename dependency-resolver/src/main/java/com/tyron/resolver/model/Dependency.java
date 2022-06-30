package com.tyron.resolver.model;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Dependency {

    public static Dependency valueOf(String declaration) {
        String[] names = declaration.split(":");
        if (names.length < 3) {
            throw new IllegalStateException("Unknown format: " + declaration);
        }
        return new Dependency(names[0], names[1], names[2]);
    }

    private String artifactId;
    private String groupId;
    private String versionName;
    private String scope;
    private String type;

    private final List<Dependency> excludes = new ArrayList<>(1);

    public Dependency() {

    }

    public Dependency(Dependency copy) {
        this.artifactId = copy.artifactId;
        this.groupId = copy.groupId;
        this.versionName = copy.versionName;
        this.scope = copy.scope;
        this.type = copy.type;
        this.excludes.addAll(copy.getExcludes());
    }

    public Dependency(String groupId, String artifactId, String versionName) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.versionName = versionName;
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

    public List<Dependency> getExcludes() {
        return excludes;
    }

    public void addExclude(Dependency dependency) {
        excludes.add(dependency);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Dependency that = (Dependency) o;
        return Objects.equals(artifactId, that.artifactId) && Objects.equals(groupId,
                that.groupId) && Objects.equals(versionName, that.versionName) && Objects.equals(scope, that.scope) && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifactId, groupId, versionName, scope, type);
    }
}