package com.tyron.builder.internal.operations;

import org.jetbrains.annotations.Nullable;

public interface BuildOperationContext {
    /**
     * Marks the build operation as failed, without throwing an exception out of the operation.
     *
     * If called with non-null, will suppress any exception thrown by the operation being used as the operation failure.
     *
     * @param failure Can be null, in which case this method does nothing.
     */
    void failed(@Nullable Throwable failure);

    /**
     * Finishes the build operation which should only be done once.
     */
    void setResult(Object result);

    /**
     * Record a status or outcome for given build operation.
     *
     * @param status operation status
     * @since 4.0
     */
    void setStatus(String status);

    /**
     * Indicates some progress of this build operation.
     */
    void progress(String status);

    /**
     * Indicates some progress of this build operation.
     */
    void progress(long progress, long total, String units, String status);
}