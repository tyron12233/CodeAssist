package com.tyron.builder.api.transform;

import com.android.annotations.NonNull;
import java.util.Collection;

/**
 * The input to a Transform.
 * <p>
 * It is mostly composed of a list of {@link JarInput} and a list of {@link DirectoryInput}.
 * @deprecated
 */
@Deprecated
public interface TransformInput {

    /**
     * Returns a collection of {@link JarInput}.
     */
    @NonNull
    Collection<JarInput> getJarInputs();

    /**
     * Returns a collection of {@link DirectoryInput}.
     */
    @NonNull
    Collection<DirectoryInput> getDirectoryInputs();
}
