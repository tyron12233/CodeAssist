package com.tyron.builder.api.artifacts.transform;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.reflect.InjectionPointQualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attach this annotation to an abstract getter that should receive the <em>artifact dependencies</em> of the {@link InputArtifact} of an artifact transform.
 *
 * <p>
 *     For example, when a project depends on {@code spring-web}, when the project is transformed (i.e. the project is the input artifact),
 *     the input artifact dependencies are the file collection containing the {@code spring-web} JAR and all its dependencies like e.g. the {@code spring-core} JAR.
 *
 *     The abstract getter must be declared as type {@link com.tyron.builder.api.file.FileCollection}.
 *     The order of the files matches that of the dependencies declared for the input artifact.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre class='autoTested'>
 * import com.tyron.builder.api.artifacts.transform.TransformParameters;
 *
 * public abstract class MyTransform implements TransformAction&lt;TransformParameters.None&gt; {
 *
 *     {@literal @}InputArtifact
 *     public abstract Provider&lt;FileSystemLocation&gt; getInputArtifact();
 *
 *     {@literal @}InputArtifactDependencies
 *     public abstract FileCollection getDependencies();
 *
 *     {@literal @}Override
 *     public void transform(TransformOutputs outputs) {
 *         FileCollection dependencies = getDependencies();
 *         // Do something with the dependencies
 *     }
 * }
 * </pre>
 *
 * @since 5.3
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
@Documented
@InjectionPointQualifier(supportedTypes = FileCollection.class)
public @interface InputArtifactDependencies {
}
