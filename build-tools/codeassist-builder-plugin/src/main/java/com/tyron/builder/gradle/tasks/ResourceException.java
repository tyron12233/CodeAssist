package com.tyron.builder.gradle.tasks;

import com.android.ide.common.resources.MergingException;

/**
 * Exception used for resource merging errors, thrown when
 * a {@link MergingException} is thrown by the resource merging code.
 * We can't just rethrow the {@linkplain MergingException} because
 * gradle 1.8 seems to want a RuntimeException; without it you get
 * the error message
 * {@code
 *     > Could not call IncrementalTask.taskAction() on task ':MyPrj:mergeDebugResources'
 * }
 */
public class ResourceException extends RuntimeException {
    public ResourceException(String message, Throwable throwable) {
        super(message, throwable);
    }
}