package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.api.transform.DirectoryInput;
import com.tyron.builder.api.transform.JarInput;
import com.tyron.builder.api.transform.QualifiedContent;
import com.tyron.builder.api.transform.QualifiedContent.ContentType;
import com.tyron.builder.api.transform.QualifiedContent.Scope;
import com.tyron.builder.api.transform.TransformInput;
import com.tyron.builder.api.transform.TransformOutputProvider;
import com.google.common.collect.Iterables;
import java.util.Set;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.util.PatternSet;

/**
 * Representation of a stream for internal usage of the {@link TransformManager} to wire up
 * the different Transforms.
 *
 * Transforms read from and write into TransformStreams, via a custom view of them:
 * {@link TransformInput}, and {@link TransformOutputProvider}.
 *
 * This contains information about the content via {@link QualifiedContent}, dependencies, and the
 * actual file information.
 *
 * The dependencies is what triggers the creation of the files and any Transform (task) consuming
 * the files must be made to depend on these objects.
 */
@Immutable
public abstract class TransformStream {

    // FIXME These objects are no immutable....
    private static final PatternSet INCLUDE_CLASSES =
            new PatternSet()
                    .include("**/*.class")
                    .include("**/*.jar")
                    .include("META-INF/*.kotlin_module");
    private static final PatternSet EXCLUDE_CLASSES = new PatternSet().exclude("**/*.class");
    private static final PatternSet INCLUDE_SO =
            new PatternSet().include("**/*.so").include("**/*.jar");
    private static final PatternSet INCLUDE_DEX =
            new PatternSet().include("**/*.dex").include("**/*.jar");
    private static final PatternSet INCLUDE_DATABINDING_BIN = new PatternSet();

    @NonNull private final String name;
    @NonNull private final Set<ContentType> contentTypes;
    @NonNull private final Set<? super Scope> scopes;
    @NonNull private final FileCollection fileCollection;

    /**
     * Creates the stream
     *
     * @param name the name of the string. This is used only for debugging purpose in order to more
     *     easily identify streams when debugging transforms. There is no restrictions on what the
     *     name can be.
     * @param contentTypes the content type(s) of the stream
     * @param scopes the scope(s) of the stream
     * @param fileCollection the file collection that makes up the content of the stream
     */
    protected TransformStream(
            @NonNull String name,
            @NonNull Set<ContentType> contentTypes,
            @NonNull Set<? super Scope> scopes,
            @NonNull FileCollection fileCollection) {
        this.name = name;
        this.contentTypes = contentTypes;
        this.scopes = scopes;
        this.fileCollection = fileCollection;
    }

    @NonNull
    public String getName() {
        return name;
    }

    /**
     * Returns the type of content that the stream represents.
     *
     * <p>
     * It's never null nor empty, but can contain several types.
     */
    @NonNull
    public Set<ContentType> getContentTypes() {
        return contentTypes;
    }

    /**
     * Returns the scope of the stream.
     *
     * <p>
     * It's never null nor empty, but can contain several scopes.
     */
    @NonNull
    public Set<? super Scope> getScopes() {
        return scopes;
    }

    /** Returns the stream content as a FileCollection. */
    @NonNull
    public FileCollection getFileCollection() {
        return fileCollection;
    }

    /**
     * Returns the content of the stream as a file tree.
     *
     * <p>This is filtered based on {@link #getContentTypes()} so that this can be used as task
     * inputs.
     *
     * @return the file tree.
     */
    @NonNull
    public FileTree getAsFileTree() {
        final FileTree fileTree = fileCollection.getAsFileTree();

        PatternSet pattern = getPatternSet();
        if (pattern != null) {
            return fileTree.matching(pattern);
        }

        return fileTree;
    }

    @Nullable
    private PatternSet getPatternSet() {
        if (contentTypes.size() == 1) {
            return getSingleTypePatternSet(Iterables.getOnlyElement(contentTypes));
        }

        // else, create a new PatternSet and try to set some valid include/exclude.
        // It's hard because some items cannot be easily match with just includes.
        if (contentTypes.size() == 2
                && contentTypes.contains(ExtendedContentType.NATIVE_LIBS)
                && contentTypes.contains(QualifiedContent.DefaultContentType.RESOURCES)) {
            // only res and native so, use an exclude on classes.
            return EXCLUDE_CLASSES;
        }

        // TODO more cases?

        return null;
    }

    @NonNull
    private static PatternSet getSingleTypePatternSet(@NonNull ContentType type) {
        if (type instanceof QualifiedContent.DefaultContentType) {
            switch ((QualifiedContent.DefaultContentType) type) {
                case CLASSES:
                    return INCLUDE_CLASSES;
                case RESOURCES:
                    // Can't make an include but since we really care about excluding class
                    // files, use an exclude pattern
                    return EXCLUDE_CLASSES;
                default:
                    throw new RuntimeException("Unsupported DefaultContentType value: " + type);
            }

        } else if (type instanceof ExtendedContentType) {
            switch ((ExtendedContentType) type) {
                case DEX:
                case DEX_ARCHIVE:
                    return INCLUDE_DEX;
                case NATIVE_LIBS:
                    return INCLUDE_SO;
                case CLASSES_ENHANCED:
                    return INCLUDE_CLASSES;
                case DATA_BINDING:
                    return INCLUDE_DATABINDING_BIN;
                default:
                    throw new RuntimeException("Unsupported ExtendedContentType value: " + type);
            }
        }

        throw new RuntimeException(
                "Unsupported ContentType implementation: " + type.getClass().getCanonicalName());
    }

    /**
     * Returns the transform input for this stream.
     *
     * <p>All the {@link JarInput} and {@link DirectoryInput} will be in non-incremental mode.
     *
     * @return the transform input.
     */
    @NonNull
    abstract TransformInput asNonIncrementalInput();

    /**
     * Returns a list of QualifiedContent for the jars and one for the folders.
     *
     */
    @NonNull
    abstract IncrementalTransformInput asIncrementalInput();

    @NonNull
    abstract TransformStream makeRestrictedCopy(
            @NonNull Set<ContentType> types,
            @NonNull Set<? super Scope> scopes);

    /**
     * Returns a FileCollection that contains the outputs.
     *
     * <p>The type/scope of the output is filtered by a StreamFilter.
     *
     * @param project a Projet object to create new FileCollection
     * @param streamFilter the stream filter.
     * @return the FileCollection
     */
    @NonNull
    abstract FileCollection getOutputFileCollection(
            @NonNull Project project, @NonNull StreamFilter streamFilter);
}
