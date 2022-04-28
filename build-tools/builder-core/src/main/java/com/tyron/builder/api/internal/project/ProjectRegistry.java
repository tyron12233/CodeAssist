package com.tyron.builder.api.internal.project;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Set;
import java.util.function.Predicate;

public interface ProjectRegistry<T extends ProjectIdentifier> {
    void addProject(T project);

    @Nullable T getRootProject();

    @Nullable T getProject(String path);

    @Nullable T getProject(File projectDir);

    int size();

    Set<T> getAllProjects();

    Set<T> getAllProjects(String path);

    Set<T> getSubProjects(String path);

    Set<T> findAll(Predicate<? super T> constraint);
}