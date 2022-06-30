package com.tyron.resolver.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Pom {

    private String artifactId;
    private String groupId;
    private String versionName;
    private String packaging;

    private boolean userDefined;

    private List<Dependency> dependencies;
    private List<Dependency> excludes;

    private final Map<String, String> properties = new HashMap<>();
    private Pom parent;

    private List<Dependency> managedDependencies;

    public Pom() {

    }

    public static Pom valueOf(String groupId, String artifactId, String versionName) {
        Pom pom = new Pom();
        pom.setGroupId(groupId);
        pom.setArtifactId(artifactId);
        pom.setVersionName(versionName);
        return pom;
    }

    public static Pom valueOf(String declaration) {
        String[] names = declaration.split(":");
        if (names.length < 3) {
            throw new IllegalStateException("Unknown format: " + declaration);
        }
        return valueOf(names[0], names[1], names[2]);
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public List<Dependency> getDependencies() {
        if (dependencies == null) {
            return Collections.emptyList();
        }
        return dependencies;
    }

    public List<Dependency> getExcludes() {
        if (excludes == null) {
            return Collections.emptyList();
        }
        return excludes;
    }

    public void setDependencies(List<Dependency> dependencies) {
        this.dependencies = dependencies;
    }

    public void setExcludes(List<Dependency> excludes) {
        this.excludes = excludes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Pom pom = (Pom) o;
        return Objects.equals(artifactId, pom.artifactId) && Objects.equals(groupId, pom.groupId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifactId, groupId);
    }

    public String getDeclarationString() {
        return groupId + ":" + artifactId + ":" + versionName;
    }

    public String getFileName() {
        return artifactId + "-" + versionName;
    }

    public String getPath() {
        String path = groupId.replace('.', '/');
        return path + "/" + artifactId + "/" + versionName;
    }

    @NonNull
    @Override
    public String toString() {
        return getDeclarationString();
    }

    public String getPackaging() {
        return packaging;
    }

    public void setUserDefined(boolean val) {
        userDefined = val;
    }

    public boolean isUserDefined() {
        return userDefined;
    }

    public void setPackaging(String packaging) {
        this.packaging = packaging;
    }

    public void addExcludes(List<Dependency> excludes) {
        if (this.excludes == null) {
            this.excludes = new ArrayList<>();
        }
        this.excludes.addAll(excludes);
    }

    public void addProperty(String key, String value) {
        properties.put(key, value);
    }

    @Nullable
    public String getProperty(String key) {
        return properties.get(key);
    }

    @Nullable
    public Pom getParent() {
        return parent;
    }

    public void setParent(Pom parent) {
        this.parent = parent;
    }

    public List<Dependency> getManagedDependencies() {
        if (managedDependencies == null) {
            managedDependencies = new ArrayList<>();
        }
        return managedDependencies;
    }

    public void setManagedDependencies(List<Dependency> dependencies) {
        managedDependencies = dependencies;
    }
}
