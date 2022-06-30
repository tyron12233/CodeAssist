package com.tyron.builder.internal.operations.notify;

import javax.annotation.Nullable;

/**
 * A notification that a build operation has started.
 *
 * The methods of this interface are awkwardly prefixed to allow
 * internal types to implement this interface along with other internal interfaces
 * without risking method collision.
 *
 * @since 4.0
 */
//@UsedByScanPlugin
public interface BuildOperationStartedNotification {

    /**
     * A unique, opaque, value identifying this operation.
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
     * The time that the operation started.
     *
     * @since 4.2
     */
    long getNotificationOperationStartedTimestamp();

    /**
     * A structured object providing details about the operation to be performed.
     */
    Object getNotificationOperationDetails();

}
