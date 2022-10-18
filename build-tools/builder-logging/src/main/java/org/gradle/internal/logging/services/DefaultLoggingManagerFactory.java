package org.gradle.internal.logging.services;

import org.gradle.internal.Factory;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.config.LoggingRouter;
import org.gradle.internal.logging.config.LoggingSourceSystem;

public class DefaultLoggingManagerFactory implements Factory<LoggingManagerInternal> {
    private final LoggingSourceSystem slfLoggingSystem;
    private final LoggingSourceSystem javaUtilLoggingSystem;
    private final LoggingSourceSystem stdOutLoggingSystem;
    private final LoggingSourceSystem stdErrLoggingSystem;
    private final DefaultLoggingManager rootManager;
    private final LoggingRouter loggingRouter;
    private boolean created;

    public DefaultLoggingManagerFactory(LoggingRouter loggingRouter, LoggingSourceSystem slf4j, LoggingSourceSystem javaUtilLoggingSystem, LoggingSourceSystem stdOutLoggingSystem, LoggingSourceSystem stdErrLoggingSystem) {
        this.loggingRouter = loggingRouter;
        this.slfLoggingSystem = slf4j;
        this.javaUtilLoggingSystem = javaUtilLoggingSystem;
        this.stdOutLoggingSystem = stdOutLoggingSystem;
        this.stdErrLoggingSystem = stdErrLoggingSystem;
        rootManager = newManager();
    }

    public LoggingManagerInternal   getRoot() {
        return rootManager;
    }

    @Override
    public LoggingManagerInternal create() {
        if (!created) {
            created = true;
            return getRoot();
        }
        return newManager();
    }

    private DefaultLoggingManager newManager() {
        return new DefaultLoggingManager(slfLoggingSystem, javaUtilLoggingSystem, stdOutLoggingSystem, stdErrLoggingSystem, loggingRouter);
    }
}

