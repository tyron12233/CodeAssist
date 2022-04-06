package com.tyron.builder.api.tasks;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Attached to a task type to indicate that task output caching should be enabled by default for tasks of this type.</p>
 *
 * <p>Only tasks that produce reproducible and relocatable output should be marked with {@code CacheableTask}.</p>
 *
 * <p>Caching for individual task instances can be enabled and disabled via {@link TaskOutputs#cacheIf(String, Spec)} or disabled via {@link TaskOutputs#doNotCacheIf(String, Spec)}.
 * Using these APIs takes precedence over the presence (or absence) of {@code @CacheableTask}.</p>
 *
 * @see DisableCachingByDefault
 *
 * @since 3.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface CacheableTask {
}