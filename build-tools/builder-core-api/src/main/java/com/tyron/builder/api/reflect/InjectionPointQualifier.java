package com.tyron.builder.api.reflect;

import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.api.provider.Provider;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated annotation can be used to inject elements of the supported types.
 *
 * <p>If both {@link #supportedTypes()} and {@link #supportedProviderTypes()} are empty, all types are supported.</p>
 *
 * @since 5.3
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.ANNOTATION_TYPE})
@Documented
public @interface InjectionPointQualifier {
    /**
     * The types which are supported for injection.
     */
    Class<?>[] supportedTypes() default {};

    /**
     * The types of {@link Provider}s supported for injection.
     * <br>
     * If e.g. {@link FileSystemLocation} is in the list, then this annotation can be used for injecting {@link org.gradle.api.provider.Provider}&lt;{@link org.gradle.api.file.FileSystemLocation}&gt;.
     *
     * @since 5.5
     */
    Class<?>[] supportedProviderTypes() default {};
}
