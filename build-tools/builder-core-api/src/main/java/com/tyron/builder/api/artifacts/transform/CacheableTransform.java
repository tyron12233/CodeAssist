package com.tyron.builder.api.artifacts.transform;

import com.tyron.builder.work.DisableCachingByDefault;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attaching this annotation to a {@link TransformAction} type it indicates that the build cache should be used for artifact transforms of this type.
 *
 * <p>Only an artifact transform that produces reproducible and relocatable outputs should be marked with {@code CacheableTransform}.</p>
 *
 * <p>
 *     Normalization must be specified for each file parameter of a cacheable transform.
 *     For example:
 * </p>
 * <pre class='autoTested'>
 * import com.tyron.builder.api.artifacts.transform.TransformParameters;
 *
 * {@literal @}CacheableTransform
 * public abstract class MyTransform implements TransformAction&lt;TransformParameters.None&gt; {
 *     {@literal @}PathSensitive(PathSensitivity.NAME_ONLY)
 *     {@literal @}InputArtifact
 *     public abstract Provider&lt;FileSystemLocation&gt; getInputArtifact();
 *
 *     {@literal @}Classpath
 *     {@literal @}InputArtifactDependencies
 *     public abstract FileCollection getDependencies();
 *
 *     {@literal @}Override
 *     public void transform(TransformOutputs outputs) {
 *         // ...
 *     }
 * }
 * </pre>
 *
 * @see DisableCachingByDefault
 *
 * @since 5.3
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface CacheableTransform {
}
