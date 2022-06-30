package com.tyron.builder.api.jvm;

import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.tasks.Input;

/**
 * Description of the modularity of a classpath.
 * <p>
 * A classpath is a list of JAR files, classes and resources folders passed to one of the JDK's commands like javac, java, or javadoc.
 * Since Java 9, these commands offer two different approaches to handle such a classpath:
 *
 * <ul>
 *   <li>A traditional classpath by using the --classpath parameter.
 *   <li>A modular classpath (module path) using the --module-path parameter.
 * </ul>
 *
 * If the --module-path is used, the Java Platform Module System (JPMS) becomes active and the module descriptors (module-info.class)
 * files are used to limit visibility of packages between modules at compile and runtime.
 * <p>
 * Wherever a classpath (a list of files and folders) is configured in Gradle, it can be accompanied by a ModularClasspathHandling object
 * to describe how the entries of that list are to be passed to the --classpath and --module-path parameters.
 *
 * @since 6.4
 */
public interface ModularitySpec {

    /**
     * Should a --module-path be inferred by analysing JARs and class folders on the classpath?
     * <p>
     * An entry is considered to be part of the module path if it meets one of the following conditions:
     * <ul>
     *   <li>It is a jar that contains a 'module-info.class'.
     *   <li>It is a class folder that contains a 'module-info.class'.
     *   <li>It is a jar with a MANIFEST that contains an 'Automatic-Module-Name' entry.
     * </ul>
     */
    @Input
    Property<Boolean> getInferModulePath();
}
