package org.gradle.api.tasks;

import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.work.InputChanges;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Attached to a task property to indicate that the task should be skipped when the value of the property is an empty
 * {@link FileCollection} or directory.</p>
 *
 * <p>If all of the inputs declared with this annotation are empty, the task will be skipped with a "NO-SOURCE" message.</p>
 *
 * <p>Consider using {@link IgnoreEmptyDirectories} as well, if the task only works on files and not on directories.</p>
 *
 * <p>Inputs annotated with this annotation can be queried for changes via {@link InputChanges#getFileChanges(FileCollection)} or {@link InputChanges#getFileChanges(Provider)}.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <ul><li>{@link InputFiles}</li>
 *
 * <li>{@link InputDirectory}</li> </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface SkipWhenEmpty {
}
