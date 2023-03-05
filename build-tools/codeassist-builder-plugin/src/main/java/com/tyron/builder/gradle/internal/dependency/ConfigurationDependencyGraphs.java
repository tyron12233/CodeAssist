package com.tyron.builder.gradle.internal.dependency;

import com.android.annotations.NonNull;
import com.tyron.builder.gradle.internal.ide.dependencies.MavenCoordinatesCacheBuildService;
import com.tyron.builder.gradle.internal.ide.level2.GraphItemImpl;
import com.tyron.builder.gradle.internal.ide.level2.JavaLibraryImpl;
import com.tyron.builder.model.level2.DependencyGraphs;
import com.tyron.builder.model.level2.GraphItem;
import com.tyron.builder.model.level2.Library;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.gradle.api.artifacts.Configuration;

/**
 * Implementation of {@link DependencyGraphs} over a Gradle
 * Configuration object. This is used to lazily query the list of files from the config object.
 *
 * This is only used when registering extra Java Artifacts.
 */
public class ConfigurationDependencyGraphs implements DependencyGraphs {

    @NonNull
    private final Configuration configuration;

    @NonNull
    private List<GraphItem> graphItems;
    private List<Library> libraries;

    public ConfigurationDependencyGraphs(@NonNull Configuration configuration) {
        this.configuration = configuration;
    }

    @NonNull
    public List<Library> getLibraries() {
        init();
        return libraries;
    }

    @NonNull
    @Override
    public List<GraphItem> getCompileDependencies() {
        init();
        return graphItems;
    }

    @NonNull
    @Override
    public List<GraphItem> getPackageDependencies() {
        init();
        return graphItems;
    }

    @NonNull
    @Override
    public List<String> getProvidedLibraries() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public List<String> getSkippedLibraries() {
        return Collections.emptyList();
    }

    private void init() {
        //noinspection ConstantConditions,VariableNotUsedInsideIf
        if (graphItems != null) {
            return;
        }

        Set<File> files = configuration.getFiles();
        if (files.isEmpty()) {
            graphItems = Collections.emptyList();
            libraries = Collections.emptyList();
            return;
        }

        graphItems = Lists.newArrayListWithCapacity(files.size());
        libraries = Lists.newArrayListWithCapacity(files.size());

        for (File file : files) {
            Library javaLib =
                    new JavaLibraryImpl(
                            MavenCoordinatesCacheBuildService.getMavenCoordForLocalFile(file)
                                    .toString(),
                            file);
            libraries.add(javaLib);
            graphItems.add(new GraphItemImpl(javaLib.getArtifactAddress(), ImmutableList.of()));
        }
    }
}
