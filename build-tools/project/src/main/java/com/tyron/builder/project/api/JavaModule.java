package com.tyron.builder.project.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.model.Library;
import com.tyron.builder.project.util.PackageTrie;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface JavaModule extends Module {

    /**
     * @return a map of fully qualified name and its java file
     */
    @NonNull
    Map<String, File> getJavaFiles();

    File getJavaFile(@NonNull String packageName);

    void removeJavaFile(@NonNull String packageName);

    void addJavaFile(@NonNull File javaFile);

    List<File> getLibraries();

    void addLibrary(@NonNull File jar);

    /**
     * Sets the map of md5 hash to its library source
     */
    void putLibraryHashes(Map<String, Library> hashes);

    /**
     *
     * @param hash the hash of the jar file
     * @return The library object for its hash
     */
    @Nullable
    Library getLibrary(String hash);

    /**
     * @return The fully qualified name of all classes in this projects including its
     * libraries
     *
     * @deprecated Use {@link JavaModule#getClassIndex()} instead for faster queries
     */
    @Deprecated
    Set<String> getAllClasses();

    @NonNull
    PackageTrie getClassIndex();

    /**
     * @return The resources directory of the project. Note that
     * this is different from android's res directory
     */
    @NonNull
    File getResourcesDir();

    /**
     * @return The directory on where java sources will be searched
     */
    @NonNull
    File getJavaDirectory();

    File getLibraryDirectory();

    /*
     * @return the libraries.json file used to store the dependencies of this module
     */
    File getLibraryFile();

    /**
     * This is required if the project uses lambdas
     *
     * @return a jar file which contains stubs for lambda compilation
     */
    File getLambdaStubsJarFile();

    /**
     * @return the bootstrap jar file which contains the necessary classes.
     * This includes {@code java.lang} package and other classes
     */
    File getBootstrapJarFile();

    Map<String, File> getInjectedClasses();

    void addInjectedClass(@NonNull File file);
}
