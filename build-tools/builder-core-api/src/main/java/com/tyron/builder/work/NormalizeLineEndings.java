package com.tyron.builder.work;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attached to an input property to specify that line endings should be normalized
 * when snapshotting inputs. Two files with the same contents but different line endings
 * will be considered equivalent.
 *
 * Line ending normalization is only supported with ASCII encoding and its supersets (i.e.
 * UTF-8, ISO-8859-1, etc).  Other encodings (e.g. UTF-16) will be treated as binary files
 * and will not be subject to line ending normalization.
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * This annotation can be applied to the following input property types:
 *
 * <ul><li>{@link org.gradle.api.tasks.InputFile}</li>
 *
 * <li>{@link org.gradle.api.tasks.InputFiles}</li>
 *
 * <li>{@link org.gradle.api.tasks.InputDirectory}</li>
 *
 * <li>{@link org.gradle.api.tasks.Classpath}</li>
 *
 * <li>{@link org.gradle.api.artifacts.transform.InputArtifact}</li>
 *
 * <li>{@link org.gradle.api.artifacts.transform.InputArtifactDependencies}</li> </ul>
 *
 * @since 7.2
 */
//@Incubating
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface NormalizeLineEndings {
}