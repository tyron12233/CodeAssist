package com.tyron.builder.internal.logging;

import org.apache.tools.ant.Project;
import com.tyron.builder.api.logging.LogLevel;

import java.util.HashMap;
import java.util.Map;

public final class LogLevelMapping {

    private LogLevelMapping() {
    }

    public static final Map<Integer, LogLevel> ANT_IVY_2_SLF4J = new HashMap<Integer, LogLevel>() {
        {
            put(Project.MSG_ERR, LogLevel.ERROR);
            put(Project.MSG_WARN, LogLevel.WARN);
            put(Project.MSG_INFO, LogLevel.INFO);
            put(Project.MSG_DEBUG, LogLevel.DEBUG);
            put(Project.MSG_VERBOSE, LogLevel.DEBUG);
        }};
}
