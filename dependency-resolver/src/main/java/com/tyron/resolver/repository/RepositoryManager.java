package com.tyron.resolver.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.resolver.model.Pom;

import java.io.File;
import java.io.IOException;

public interface RepositoryManager {

    /**
     * Retrieve the pom file either from cache or from the network
     * @param declaration Declaration string in this format {@code groupId:artifactId:version}
     * @return null if the pom file is not found on the cache or the urls
     */
    @Nullable
    Pom getPom(String declaration);

    /**
     * @return May return a jar or aar depending on the packaging type of the given pom.
     */
    @Nullable
    File getLibrary(Pom pom) throws IOException;

    void setCacheDirectory(File directory);

    /**
     * Adds a repository to search for
     * @param name the name of the cache directory
     * @param url The url of the repository
     */
    void addRepository(@NonNull String name, @NonNull String url);

    /**
     * Adds a repository to search for
     * @param repository the repository to add
     */
    void addRepository(@NonNull Repository repository);

    void initialize();
}
