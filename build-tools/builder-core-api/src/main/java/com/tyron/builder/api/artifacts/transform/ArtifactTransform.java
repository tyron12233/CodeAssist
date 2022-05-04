package com.tyron.builder.api.artifacts.transform;

import java.io.File;
import java.util.List;

/**
 * Base class for artifact transforms.
 *
 * <p>Implementations must provide a public constructor. The constructor may optionally accept parameters, in which case it must be annotated with {@link javax.inject.Inject}. The following parameters are available:</p>
 *
 * <ul>
 * <li>The objects provided to {@link org.gradle.api.ActionConfiguration#setParams(Object...)}.</li>
 * </ul>
 *
 * @deprecated Use {@link TransformAction} instead.
 * @since 3.4
 */
@Deprecated
public abstract class ArtifactTransform {
    private File outputDirectory;

    /**
     * Returns the <em>workspace</em> location for this transform, which is the directory that the transform should write its output files to.
     */
    public File getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * Transforms the given <em>input artifact</em> file or directory and returns the result.
     *
     * @param input The input file or directory.
     * @return The output files or directories. Can return an empty list.
     */
    public abstract List<File> transform(File input);
}
