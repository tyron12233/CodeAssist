package com.tyron.builder.internal.watch.registry;

import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

public enum WatchMode {
    ENABLED(true, "enabled") {
        @Override
        public Logger loggerForWarnings(Logger currentLogger) {
            return currentLogger;
        }
    },
    DEFAULT(true, "enabled if available") {
        @Override
        public Logger loggerForWarnings(Logger currentLogger) {
            return currentLogger.isInfoEnabled() ? currentLogger : NOPLogger.NOP_LOGGER;
        }
    },
    DISABLED(false, "disabled") {
        @Override
        public Logger loggerForWarnings(Logger currentLogger) {
            return NOPLogger.NOP_LOGGER;
        }
    };

    private final boolean enabled;
    private final String description;

    WatchMode(boolean enabled, String description) {
        this.enabled = enabled;
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public abstract Logger loggerForWarnings(Logger currentLogger);

    public String getDescription() {
        return description;
    }
}
