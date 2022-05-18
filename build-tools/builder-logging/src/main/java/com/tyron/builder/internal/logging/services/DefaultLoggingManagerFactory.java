package com.tyron.builder.internal.logging.services;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.logging.config.LoggingRouter;
import com.tyron.builder.internal.logging.config.LoggingSourceSystem;

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

