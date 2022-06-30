package com.tyron.builder.internal.operations.notify;

/**
 * A notification that a build operation has finished.
 *
 * The methods of this interface are awkwardly prefixed to allow
 * internal types to implement this interface along with other internal interfaces
 * without risking method collision.
 *
 * @since 4.4
 */
//@UsedByScanPlugin
public interface BuildOperationProgressNotification {

    /**
     * The operation ID.
     *
     * Must be logically equal to a {@link BuildOperationStartedNotification#getNotificationOperationId()} value
     * of a previously emitted started notification.
     */
    Object getNotificationOperationId();

    /**
     * The time that the event occurred.
     *
     * @since 4.4
     */
    long getNotificationOperationProgressTimestamp();

    /**
     * A structured object providing details about the operation that was performed.
     */
    Object getNotificationOperationProgressDetails();

}
