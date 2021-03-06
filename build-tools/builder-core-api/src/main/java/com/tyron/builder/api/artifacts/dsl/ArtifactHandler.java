package com.tyron.builder.api.artifacts.dsl;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.ConfigurablePublishArtifact;
import com.tyron.builder.api.artifacts.PublishArtifact;

/**
 * This class is for defining artifacts to be published and adding them to configurations. Creating publish artifacts
 * does not mean to create an archive. What is created is a domain object which represents a file to be published
 * and information on how it should be published (e.g. the name).
 *
 * <p>To create an publish artifact and assign it to a configuration you can use the following syntax:</p>
 *
 * <code>&lt;configurationName&gt; &lt;artifact-notation&gt;, &lt;artifact-notation&gt; ...</code>
 *
 * or
 *
 * <code>&lt;configurationName&gt; &lt;artifact-notation&gt; { ... some code to configure the artifact }</code>
 *
 * <p>The notation can be one of the following types:</p>
 *
 * <ul>
 *
 * <li>{@link PublishArtifact}.</li>
 *
 * <li>{@link com.tyron.builder.api.tasks.bundling.AbstractArchiveTask}. The information for publishing the artifact is extracted from the archive task (e.g. name, extension, ...). The task will be executed if the artifact is required.</li>
 *
 * <li>A {@link com.tyron.builder.api.file.RegularFile} or {@link com.tyron.builder.api.file.Directory}.</li>
 *
 * <li>A {@link com.tyron.builder.api.provider.Provider} of {@link java.io.File}, {@link com.tyron.builder.api.file.RegularFile}, {@link com.tyron.builder.api.file.Directory} or {@link com.tyron.builder.api.Task}, with the limitation that the latter has to define a single file output property. The information for publishing the artifact is extracted from the file or directory name. When the provider represents an output of a particular task, that task will be executed if the artifact is required.</li>
 *
 * <li>{@link java.io.File}. The information for publishing the artifact is extracted from the file name.</li>
 *
 * <li>{@link java.util.Map}. The map should contain a 'file' key. This is converted to an artifact as described above. You can also specify other properties of the artifact using entries in the map.
 * </li>
 *
 * </ul>
 *
 * <p>In each case, a {@link ConfigurablePublishArtifact} instance is created for the artifact, to allow artifact properties to be configured. You can also override the default values for artifact properties by using a closure to configure the properties of the artifact instance</p>
 *
 * <h2>Examples</h2>
 * <p>An example showing how to associate an archive task with a configuration via the artifact handler.
 * This way the archive can be published or referred in other projects via the configuration.
 * <pre class='autoTested'>
 * configurations {
 *   //declaring new configuration that will be used to associate with artifacts
 *   schema
 * }
 *
 * task schemaJar(type: Jar) {
 *   //some imaginary task that creates a jar artifact with some schema
 * }
 *
 * //associating the task that produces the artifact with the configuration
 * artifacts {
 *   //configuration name and the task:
 *   schema schemaJar
 * }
 * </pre>
 */
public interface ArtifactHandler {
    /**
     * Adds an artifact to the given configuration.
     *
     * @param configurationName The name of the configuration.
     * @param artifactNotation The artifact notation, in one of the notations described above.
     * @return The artifact.
     */
    PublishArtifact add(String configurationName, Object artifactNotation);

    /**
     * Adds an artifact to the given configuration.
     *
     * @param configurationName The name of the configuration.
     * @param artifactNotation The artifact notation, in one of the notations described above.
     * @param configureClosure The closure to execute to configure the artifact.
     * @return The artifact.
     */
    PublishArtifact add(String configurationName, Object artifactNotation, Closure configureClosure);

    /**
     * Adds an artifact to the given configuration.
     *
     * @param configurationName The name of the configuration.
     * @param artifactNotation The artifact notation, in one of the notations described above.
     * @param configureAction The action to execute to configure the artifact.
     * @return The artifact.
     *
     * @since 3.3.
     */
    PublishArtifact add(String configurationName, Object artifactNotation, Action<? super ConfigurablePublishArtifact> configureAction);
}
