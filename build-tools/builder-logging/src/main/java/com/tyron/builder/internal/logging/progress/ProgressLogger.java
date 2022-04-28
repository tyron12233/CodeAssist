package com.tyron.builder.internal.logging.progress;

import org.jetbrains.annotations.Nullable;

/**
 * Used to log the progress of a potentially long running operation.
 *
 * <p>When running in the command-line UI, the properties of an operation are treated as follows:
 *
 * <ul>
 *
 * <li>When an operation starts, and the operation has a logging header defined, a LIFECYCLE log message is generated containing the logging header.
 * If running under a terminal, and the logging header == the short description or the status, this log message is deferred until either some other log
 * message is generated or the operation completes.</li>
 *
 * <li>If running under a terminal, and the operation has a status defined, that status is shown in the 'status bar' at the bottom of the screen.</li>
 *
 * <li>If running under a terminal, and the operation has a short description and no status defined, the short description is shown in the 'status bar' at the bottom of the screen.</li>
 *
 * </ul>
 *
 */
public interface ProgressLogger {

    ProgressLogger EMPTY = new ProgressLogger() {
        @Override
        public String getDescription() {
            return "Empty LOGGER";
        }

        @Override
        public ProgressLogger setDescription(String description) {
            return this;
        }

        @Override
        public ProgressLogger start(String description, String status) {
            return this;
        }

        @Override
        public void started() {

        }

        @Override
        public void started(String status) {

        }

        @Override
        public void progress(@Nullable String status) {

        }

        @Override
        public void progress(@Nullable String status, boolean failing) {

        }

        @Override
        public void completed() {

        }

        @Override
        public void completed(String status, boolean failed) {

        }
    };

    /**
     * Returns the description of the operation.
     *
     * @return the description, must not be empty.
     */
    String getDescription();

    /**
     * <p>Sets the description of the operation. This should be a full, stand-alone description of the operation.
     *
     * <p>This must be called before {@link #started()}.
     *
     * @param description The description.
     */
    ProgressLogger setDescription(String description);

    /**
     * Convenience method that sets descriptions and logs started() event.
     *
     * @param status The initial status message. Can be null or empty.
     * @return this logger instance
     */
    ProgressLogger start(String description, String status);

    /**
     * Logs the start of the operation, with no initial status.
     */
    void started();

    /**
     * Logs the start of the operation, with the given status.
     *
     * @param status The initial status message. Can be null or empty.
     */
    void started(String status);

    /**
     * Logs some progress, indicated by a new status.
     *
     * @param status The new status message. Can be null or empty.
     */
    void progress(@Nullable String status);

    /**
     * Logs some progress, indicated by a new status with the given build health.
     *
     * @param status The new status message. Can be null or empty.
     */
    void progress(@Nullable String status, boolean failing);

    /**
     * Logs the completion of the operation, with no final status
     */
    void completed();

    /**
     * Logs the completion of the operation, with a final status. This is generally logged along with the description.
     *
     * @param status The final status message. Can be null or empty.
     * @param failed Did the task fail?
     */
    void completed(String status, boolean failed);
}