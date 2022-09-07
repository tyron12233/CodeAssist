package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.tyron.builder.api.transform.DirectoryInput;
import com.tyron.builder.api.transform.JarInput;
import com.tyron.builder.api.transform.QualifiedContent;
import com.tyron.builder.api.transform.Status;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Version of Transform Input to incrementally build the {@link JarInput} and {@link DirectoryInput}
 * that contains incremental file change information.
 */
abstract class IncrementalTransformInput {

    /**
     * List of JarInputs for which we haven't found a match with a changed file.
     */
    @NonNull
    private final Map<File, QualifiedContent> jarInputs = Maps.newHashMap();

    /**
     * List of folder inputs used to process changed files.
     */
    @NonNull
    private final List<MutableDirectoryInput> folderInputs = Lists.newArrayList();

    /**
     * List of JarInputs that are already matched to a changed (jar) file.
     */
    private final List<JarInput> convertedJarInputs = Lists.newArrayList();

    protected IncrementalTransformInput() {
    }

    /**
     * Process a changed file against the known list of jar files
     *
     * If the file matches a jar, creates an internal ImmutableJarInput and return true.
     * @param file the changed (jar?) file
     * @param status the file status
     * @return true if the file is a match, false otherwise.
     */
    boolean checkForJar(@NonNull File file, @NonNull Status status) {
        if (jarInputs.containsKey(file)) {
            QualifiedContent jarContent = jarInputs.get(file);
            addImmutableJar(new ImmutableJarInput(jarContent, status));
            jarInputs.remove(file);
            return true;
        }

        return false;
    }

    /**
     * Process a changed file against the known list of folders
     *
     * If the file is contains within a known folder, records it and return true
     * @param file the changed file
     * @param fileSegments the changed file path segments for faster checks
     * @param status the file status
     * @return true if the file is a match, false otherwise.
     */
    boolean checkForFolder(
            @NonNull File file,
            @NonNull List<String> fileSegments,
            @NonNull Status status) {
        for (MutableDirectoryInput folderInput : folderInputs) {
            if (folderInput.processForChangedFile(file, fileSegments, status)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Process a removed file to see if it belonged to a folder of this input.
     *
     * @param transformScopes the scopes consumed or referenced by the transform
     * @param transformInputTypes the input {@see ContentType} types of the transform.
     * @param file the removed file
     * @param fileSegments the removed file path segments for faster checks.
     * @return true if the file was part of this input.
     */
    boolean checkRemovedFolderFile(
            @NonNull Set<? super QualifiedContent.Scope> transformScopes,
            @NonNull Set<QualifiedContent.ContentType> transformInputTypes,
            @NonNull File file, @NonNull List<String> fileSegments) {
        // first check for removed files in existing folders.
        for (MutableDirectoryInput folderInput : folderInputs) {
            if (folderInput.processForChangedFile(file, fileSegments, Status.REMOVED)) {
                return true;
            }
        }

        // if we don't find anything, see if we figure out scopes/types of the removed file (and
        // its root folder.
        return checkRemovedFolder(transformScopes, transformInputTypes, file, fileSegments);
    }

    /**
     * Process a removed file to see if it was a jar belonging to this input.
     *
     * @param transformScopes the scopes consumed or referenced by the transform
     * @param transformInputTypes the input {@see ContentType} types of the transform.
     * @param file the removed file
     * @param fileSegments the removed file path segments for faster checks.
     * @return true if the file was part of this input.
     */
    abstract boolean checkRemovedJarFile(
            @NonNull Set<? super QualifiedContent.Scope> transformScopes,
            @NonNull Set<QualifiedContent.ContentType> transformInputTypes,
            @NonNull File file,
            @NonNull List<String> fileSegments);

    /**
     * Process a removed file to see if it belonged to a removed folder of this input.
     *
     *
     *
     * @param transformScopes transform scopes
     * @param transformInputTypes transform input types
     * @param file the removed file
     * @param fileSegments the removed file path segments for faster checks.
     * @return true if the file was part of this input.
     */
    protected abstract boolean checkRemovedFolder(
            @NonNull Set<? super QualifiedContent.Scope> transformScopes,
            @NonNull Set<QualifiedContent.ContentType> transformInputTypes,
            @NonNull File file,
            @NonNull List<String> fileSegments);


    void addJarInput(@NonNull QualifiedContent jarInput) {
        jarInputs.put(jarInput.getFile(), jarInput);
    }

    protected void addImmutableJar(@NonNull ImmutableJarInput jarInput) {
        convertedJarInputs.add(jarInput);
    }

    void addFolderInput(@NonNull MutableDirectoryInput folderInput) {
        folderInputs.add(folderInput);
    }

    @NonNull
    ImmutableTransformInput asImmutable() {
        // create a list with all the touched jars + the untouched ones.
        List<JarInput> immutableJarInputs = Lists.newArrayListWithCapacity(
                jarInputs.size() + convertedJarInputs.size());
        immutableJarInputs.addAll(convertedJarInputs);
        // add untouched jars.
        for (QualifiedContent jarContent : jarInputs.values()) {
            immutableJarInputs.add(new ImmutableJarInput(jarContent, Status.NOTCHANGED));
        }

        // now create the list for the folder inputs.
        List<DirectoryInput> immutableDirectoryInputs = Lists.newArrayListWithCapacity(
                folderInputs.size());
        for (MutableDirectoryInput folderInput : folderInputs) {
            immutableDirectoryInputs.add(folderInput.asImmutable());
        }

        return new ImmutableTransformInput(immutableJarInputs, immutableDirectoryInputs, null);
    }
}
