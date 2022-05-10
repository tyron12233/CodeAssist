package com.tyron.builder.internal.operations.notify;

import javax.annotation.Nullable;

/**
 * A notification that a build operation has finished.
 *
 * The methods of this interface are awkwardly prefixed to allow
 * internal types to implement this interface along with other internal interfaces
 * without risking method collision.
 *
 * @since 4.0
 */
//@UsedByScanPlugin
public interface BuildOperationFinishedNotification {

    /**
     * The operation ID.
     *
     * Must be logically equal to a {@link BuildOperationStartedNotification#getNotificationOperationId()} value
     * of a previously emitted started notification.
     */
    Object getNotificationOperationId();

    /**
     * The ID of the parent of this notification.
     *
     * Note: this is the ID of the nearest parent operation that also resulted in a notification.
     * As notifications are not sent for all operations, this may be a different value to the
     * parent operation ID.
     *
     * Null if the operation has no parent.
     */
    @Nullable
    Object getNotificationOperationParentId();

    /**
     * The time that the operation finished.
     *
     * @since 4.2
     */
    long getNotificationOperationFinishedTimestamp();

    /**
     * A structured object providing details about the operation that was performed.
     */
    Object getNotificationOperationDetails();

    /**
     * A structured object representing the outcome of the operation.
     * Null if the operation failed, or if no result details are produced for the type of operation.
     */
    Object getNotificationOperationResult();

    /**
     * The operation failure.
     * Null if the operation was successful.
     */
    Throwable getNotificationOperationFailure();

}
