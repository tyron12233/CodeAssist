package com.tyron.builder.api.tasks;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attached to an input property to specify that directories should be ignored
 * when snapshotting inputs. Files within directories and subdirectories will be
 * snapshot, but the directories themselves will be ignored. Empty directories,
 * and directories that contain only empty directories will have no effect on the
 * resulting snapshot.
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * This annotation can be applied to the following input property types:
 *
 * <ul><li>{@link InputFiles}</li>
 *
 * <li>{@link InputDirectory}</li>
 *
 * <li>{@link InputArtifact}</li>
 *
 * <li>{@link InputArtifactDependencies}</li> </ul>
 *
 * @since 6.8
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface IgnoreEmptyDirectories {
}