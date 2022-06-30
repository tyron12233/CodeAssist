package com.tyron.builder.api.internal.project.ant;

import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.BuildLogger;
import com.tyron.builder.api.AntBuilder.AntMessagePriority;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.internal.logging.LogLevelMapping;

import java.io.PrintStream;

public class AntLoggingAdapter implements BuildLogger {
    private final Logger logger = Logging.getLogger(AntLoggingAdapter.class);

    private AntMessagePriority lifecycleLogLevel;

    @Override
    public void setMessageOutputLevel(int level) {
        // ignore
    }

    @Override
    public void setOutputPrintStream(PrintStream output) {
        // ignore
    }

    @Override
    public void setEmacsMode(boolean emacsMode) {
        // ignore
    }

    @Override
    public void setErrorPrintStream(PrintStream err) {
        // ignore
    }

    @Override
    public void buildStarted(BuildEvent event) {
        // ignore
    }

    @Override
    public void buildFinished(BuildEvent event) {
        // ignore
    }

    @Override
    public void targetStarted(BuildEvent event) {
        // ignore
    }

    @Override
    public void targetFinished(BuildEvent event) {
        // ignore
    }

    @Override
    public void taskStarted(BuildEvent event) {
        // ignore
    }

    @Override
    public void taskFinished(BuildEvent event) {
        // ignore
    }

    @Override
    public void messageLogged(BuildEvent event) {
        final StringBuffer message = new StringBuffer();
        if (event.getTask() != null) {
            String taskName = event.getTask().getTaskName();
            message.append("[ant:").append(taskName).append("] ");
        }
        final String messageText = event.getMessage();
        message.append(messageText);

        LogLevel level = getLogLevelForMessagePriority(event.getPriority());

        if (event.getException() != null) {
            logger.log(level, message.toString(), event.getException());
        } else {
            logger.log(level, message.toString());
        }
    }

    public void setLifecycleLogLevel(String lifecycleLogLevel) {
        setLifecycleLogLevel(lifecycleLogLevel == null ? null : AntMessagePriority.valueOf(lifecycleLogLevel));
    }

    public void setLifecycleLogLevel(AntMessagePriority lifecycleLogLevel) {
        this.lifecycleLogLevel = lifecycleLogLevel;
    }

    public AntMessagePriority getLifecycleLogLevel() {
        return lifecycleLogLevel;
    }

    private LogLevel getLogLevelForMessagePriority(int messagePriority) {
        LogLevel defaultLevel = LogLevelMapping.ANT_IVY_2_SLF4J.get(messagePriority);

        // Check to see if we should adjust the level based on a set lifecycle log level
        if (lifecycleLogLevel != null) {
            if (defaultLevel.ordinal() < LogLevel.LIFECYCLE.ordinal()
                && AntMessagePriority.from(messagePriority).ordinal() >= lifecycleLogLevel.ordinal()) {
                // we would normally log at a lower level than lifecycle, but the Ant message priority is actually higher
                // than (or equal to) the set lifecycle log level
                return LogLevel.LIFECYCLE;
            } else if (defaultLevel.ordinal() >= LogLevel.LIFECYCLE.ordinal()
                && AntMessagePriority.from(messagePriority).ordinal() < lifecycleLogLevel.ordinal()) {
                // would normally log at a level higher than (or equal to) lifecycle, but the Ant message priority is
                // actually lower than the set lifecycle log level
                return LogLevel.INFO;
            }
        }

        return defaultLevel;
    }
}
