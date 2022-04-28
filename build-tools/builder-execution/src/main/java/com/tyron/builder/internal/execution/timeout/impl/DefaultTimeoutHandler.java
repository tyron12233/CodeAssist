package com.tyron.builder.internal.execution.timeout.impl;

import com.google.common.io.CharStreams;
import com.tyron.builder.api.Describable;
import com.tyron.builder.internal.concurrent.ManagedScheduledExecutor;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.execution.timeout.Timeout;
import com.tyron.builder.internal.execution.timeout.TimeoutHandler;
import com.tyron.builder.internal.operations.BuildOperationRef;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.time.CountdownTimer;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.TimeFormatting;

import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class DefaultTimeoutHandler implements TimeoutHandler, Stoppable {

    private static final Logger LOGGER = Logger.getLogger(DefaultTimeoutHandler.class.getSimpleName());

    // Only intended to be used for integration testing
    public static final String POST_TIMEOUT_CHECK_FREQUENCY_PROPERTY = DefaultTimeoutHandler.class.getName() + ".postTimeoutCheckFrequency";
    public static final String SLOW_STOP_LOG_STACKTRACE_FREQUENCY_PROPERTY = DefaultTimeoutHandler.class.getName() + ".slowStopLogStacktraceFrequency";

    private final ManagedScheduledExecutor executor;
    private final CurrentBuildOperationRef currentBuildOperationRef;

    public DefaultTimeoutHandler(ManagedScheduledExecutor executor, CurrentBuildOperationRef currentBuildOperationRef) {
        this.executor = executor;
        this.currentBuildOperationRef = currentBuildOperationRef;
    }

    @Override
    public Timeout start(Thread taskExecutionThread, Duration timeout, Describable workUnitDescription, @Nullable BuildOperationRef buildOperationRef) {
        return new DefaultTimeout(taskExecutionThread, timeout, workUnitDescription, buildOperationRef);
    }

    @Override
    public void stop() {
        executor.stop();
    }

    // Value is queried “dynamically” to support testing
    private static long postTimeoutCheckFrequency() {
        return Integer.parseInt(System.getProperty(POST_TIMEOUT_CHECK_FREQUENCY_PROPERTY, "3000"));
    }

    // Value is queried “dynamically” to support testing
    private static long slowStopLogStacktraceFrequency() {
        return Integer.parseInt(System.getProperty(SLOW_STOP_LOG_STACKTRACE_FREQUENCY_PROPERTY, "10000"));
    }

    private final class DefaultTimeout implements Timeout {

        private final Thread thread;
        private final Duration timeout;
        private final Describable workUnitDescription;

        @Nullable
        private final BuildOperationRef buildOperationRef;

        private final Object lock = new Object();

        private boolean slowStop;
        private boolean stopped;
        private boolean interrupted;

        private StackTraceElement[] lastStacktrace;
        private CountdownTimer logStacktraceTimer;

        private ScheduledFuture<?> scheduledFuture;

        private DefaultTimeout(Thread thread, Duration timeout, Describable workUnitDescription, @Nullable BuildOperationRef buildOperationRef) {
            this.thread = thread;
            this.timeout = timeout;
            this.workUnitDescription = workUnitDescription;
            this.buildOperationRef = buildOperationRef;
            this.scheduledFuture = executor.schedule(this::onTimeout, timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void onTimeout() {
            synchronized (lock) {
                if (!stopped) {
                    interrupted = true;
                    doAsPartOfBuildOperation(() -> LOGGER.warning("Requesting stop of " + workUnitDescription.getDisplayName() + " as it has exceeded its configured timeout of " + TimeFormatting
                            .formatDurationTerse(timeout.toMillis())));
                    thread.interrupt();
                    logStacktraceTimer = Time.startCountdownTimer(slowStopLogStacktraceFrequency());
                    scheduledFuture = executor.schedule(this::onAfterTimeoutCheck, postTimeoutCheckFrequency(), TimeUnit.MILLISECONDS);
                }
            }
        }

        private void onAfterTimeoutCheck() {
            synchronized (lock) {
                if (!stopped) {
                    slowStop = true;
                    doAsPartOfBuildOperation(() -> {
                        LOGGER.warning("Timed out " + workUnitDescription.getDisplayName() + " has not yet stopped.");

                        if (logStacktraceTimer.hasExpired()) {
                            StackTraceElement[] currentStackTrace = thread.getStackTrace();
                            if (currentStackTrace.length > 0 && !Arrays.equals(lastStacktrace, currentStackTrace)) {
                                lastStacktrace = currentStackTrace;
                                logStacktrace(currentStackTrace);
                            }
                            logStacktraceTimer.reset();
                        }
                    });

                    thread.interrupt(); // interrupt again in case the work unit cleared the interrupt.

                    scheduledFuture = executor.schedule(this::onAfterTimeoutCheck, postTimeoutCheckFrequency(), TimeUnit.MILLISECONDS);
                }
            }
        }

        private void logStacktrace(StackTraceElement[] currentStackTrace) {
//            if (LOGGER.isWarnEnabled()) {
                // Assemble string this way so it is logged atomically, and with platform line endings
                StringBuilder logMessageBuilder = new StringBuilder();
                try (PrintWriter logMessageWriter = new PrintWriter(CharStreams.asWriter(logMessageBuilder))) {
                    logMessageWriter.print("Current stacktrace of timed out but not yet stopped ");
                    logMessageWriter.print(workUnitDescription.getDisplayName());
                    logMessageWriter.println(":");
                    for (StackTraceElement traceElement : currentStackTrace) {
                        logMessageWriter.println("  at " + traceElement);
                    }
                }
                LOGGER.warning(logMessageBuilder.toString());
//            }
        }

        private void doAsPartOfBuildOperation(Runnable runnable) {
            BuildOperationRef previousBuildOperationRef = currentBuildOperationRef.get();
            try {
                currentBuildOperationRef.set(this.buildOperationRef);
                runnable.run();
            } finally {
                currentBuildOperationRef.set(previousBuildOperationRef);
            }
        }

        @Override
        public boolean stop() {
            synchronized (lock) {
                scheduledFuture.cancel(true);
                stopped = true;
                if (slowStop) {
                    LOGGER.warning("Timed out " + workUnitDescription.getDisplayName() + " has stopped.");
                }
                return interrupted;
            }
        }
    }
}