package com.tyron.builder.internal.operations.notify;

/**
 * A listener to notifications about build events.
 *
 * Implementations are thread safe and can be signalled concurrently.
 * However, a finished signal must not be emitted before the signal of the
 * corresponding started event has returned.
 *
 * Implementations may retain the notification values beyond the method that passed them.
 * Started notifications maybe held until a corresponding finished notification, and slightly after.
 * Finished notifications maybe held for a short time after the return of the finished signal for
 * off thread processing.
 *
 * Implementations must not error from either signal.
 * Callers should ignore any exceptions thrown by these methods.
 *
 * @since 4.4
 */
//@UsedByScanPlugin("implemented by plugin")
public interface BuildOperationNotificationListener {

    void started(BuildOperationStartedNotification notification);

    void progress(BuildOperationProgressNotification notification);

    void finished(BuildOperationFinishedNotification notification);

}
