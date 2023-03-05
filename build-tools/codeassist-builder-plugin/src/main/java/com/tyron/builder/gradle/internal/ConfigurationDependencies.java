package com.tyron.builder.gradle.internal;

import com.android.annotations.NonNull;
import com.tyron.builder.gradle.internal.ide.JavaLibraryImpl;
import com.tyron.builder.dependency.MavenCoordinatesImpl;
import com.tyron.builder.model.AndroidLibrary;
import com.tyron.builder.model.Dependencies;
import com.tyron.builder.model.JavaLibrary;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import org.gradle.api.artifacts.Configuration;

/**
 * Implementation of {@link com.tyron.builder.model.Dependencies} over a Gradle
 * Configuration object. This is used to lazily query the list of files from the config object.
 *
 * This is only used when registering extra Java Artifacts.
 */
public class ConfigurationDependencies implements Dependencies {

    @NonNull
    private final Configuration configuration;

    public ConfigurationDependencies(@NonNull Configuration configuration) {

        this.configuration = configuration;
    }

    @NonNull
    @Override
    public Collection<AndroidLibrary> getLibraries() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public Collection<JavaLibrary> getJavaLibraries() {
        Set<File> files = configuration.getFiles();
        if (files.isEmpty()) {
            return Collections.emptySet();
        }
        Set<JavaLibrary> javaLibraries = Sets.newHashSet();
        int index = 1;
        for (File file : files) {
            javaLibraries.add(
                    new JavaLibraryImpl(
                            file,
                            null, /* buildId */
                            null /*projectPath*/,
                            ImmutableList.<JavaLibrary>of(),
                            null /*requestedCoordinate*/,
                            MavenCoordinatesImpl.create(
                                    // no-op impl of stringCachingService
                                    string -> string,
                                    "unknown-" + configuration.getName(),
                                    "unknown" + (index++),
                                    "unspecified"),
                            false /*isSkipped*/,
                            false /*isProvided*/));
        }
        return javaLibraries;
    }

    @NonNull
    @Override
    public Collection<String> getProjects() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public Collection<ProjectIdentifier> getJavaModules() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public Collection<File> getRuntimeOnlyClasses() {
        return Collections.emptyList();
    }
}
