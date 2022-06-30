package com.tyron.builder.internal.dispatch;

import com.tyron.builder.internal.concurrent.Stoppable;
import org.slf4j.Logger;

public class ExceptionTrackingFailureHandler implements DispatchFailureHandler<Object>, Stoppable {
    private final Logger logger;
    private DispatchException failure;

    public ExceptionTrackingFailureHandler(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void dispatchFailed(Object message, Throwable failure) {
        if (this.failure != null && !Thread.currentThread().isInterrupted()) {
            logger.error(failure.getMessage(), failure);
        } else {
            this.failure = new DispatchException(String.format("Could not dispatch message %s.", message), failure);
        }
    }

    @Override
    public void stop() throws DispatchException {
        if (failure != null) {
            try {
                throw failure;
            } finally {
                failure = null;
            }
        }
    }
}
