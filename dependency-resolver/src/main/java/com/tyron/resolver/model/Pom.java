package com.tyron.resolver.model;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Pom {

    private String artifactId;
    private String groupId;
    private String versionName;
    private String packaging;

    private List<Dependency> dependencies;

    public Pom() {

    }

    public static Pom valueOf(String groupId, String artifactId, String versionName) {
        Pom pom = new Pom();
        pom.setGroupId(groupId);
        pom.setArtifactId(artifactId);
        pom.setVersionName(versionName);
        return pom;
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

    public void setDependencies(List<Dependency> dependencies) {
        this.dependencies = dependencies;
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
        String artifact = artifactId.replace('.', '/');
        return path + "/" + artifact + "/" + versionName;
    }

    @Override
    public String toString() {
        return getDeclarationString();
    }

    public String getPackaging() {
        return packaging;
    }

    public void setPackaging(String packaging) {
        this.packaging = packaging;
    }
}
