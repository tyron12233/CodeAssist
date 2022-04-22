package com.tyron.builder.internal.logging.progress;


import com.tyron.builder.internal.operations.BuildOperationDescriptor;

/**
 * Thread-safe, however the progress logger instances created are not.
 */
public interface ProgressLoggerFactory {

    ProgressLoggerFactory EMPTY = new ProgressLoggerFactory() {
        @Override
        public ProgressLogger newOperation(String loggerCategory) {
            return ProgressLogger.EMPTY;
        }

        @Override
        public ProgressLogger newOperation(Class<?> loggerCategory) {
            return ProgressLogger.EMPTY;
        }

        @Override
        public ProgressLogger newOperation(Class<?> loggerCategory,
                                           BuildOperationDescriptor buildOperationDescriptor) {
            return ProgressLogger.EMPTY;
        }

        @Override
        public ProgressLogger newOperation(Class<?> loggerClass, ProgressLogger parent) {
            return ProgressLogger.EMPTY;
        }
    };

    /**
     * Creates a new long-running operation which has not been started.
     *
     * @param loggerCategory The logger category.
     * @return The progress logger for the operation.
     */
    ProgressLogger newOperation(String loggerCategory);

    /**
     * Creates a new long-running operation which has not been started.
     *
     * @param loggerCategory The logger category.
     * @return The progress logger for the operation.
     */
    ProgressLogger newOperation(Class<?> loggerCategory);

    /**
     * Creates a new long-running operation which has not been started, associated
     * with the given build operation id.
     *
     * @param loggerCategory The logger category.
     * @param buildOperationDescriptor descriptor for the build operation associated with this logger.
     * @return the progress logger for the operation.
     */
    ProgressLogger newOperation(Class<?> loggerCategory, BuildOperationDescriptor buildOperationDescriptor);

    ProgressLogger newOperation(Class<?> loggerClass, ProgressLogger parent);
}