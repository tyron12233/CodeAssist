package com.tyron.builder.api.tasks;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Marks a property as specifying a JVM classpath for a task.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <p>
 *     For jar files, the normalized path is empty.
 *     The content of the jar file is normalized so that time stamps and order of the zip entries in the jar file do not matter.
 *     This normalization applies to not only files directly on the classpath, but also
 *     to any jar files found inside directories or nested inside other jar files.
 *     If a directory is a classpath entry, then the root directory itself is ignored.
 *     The files in the directory are sorted and the relative path to the root directory is used as normalized path.
 * </p>
 *
 * <p><strong>Note:</strong> to stay compatible with versions prior to Gradle 3.2, classpath
 * properties need to be annotated with {@literal @}{@link InputFiles} as well.</p>
 *
 * @since 3.2
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface Classpath {
}