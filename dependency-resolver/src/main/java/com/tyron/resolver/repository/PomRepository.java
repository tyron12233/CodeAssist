package com.tyron.resolver.repository;

import com.tyron.resolver.model.Pom;

import java.io.File;

public interface PomRepository {

    Pom getPom(String declaration);

    File getJarFile(Pom pom);

    void setCacheDirectory(File directory);

    void addRepositoryUrl(String url);

    void initialize();
}
