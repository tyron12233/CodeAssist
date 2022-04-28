package com.tyron.builder.log;

import com.tyron.builder.model.DiagnosticWrapper;

import java.util.List;

public class LogUtils {

    public static void log(List<DiagnosticWrapper> logs, ILogger logger) {
        for (DiagnosticWrapper log : logs) {
            log(log, logger);
        }
    }

    public static void log(DiagnosticWrapper log, ILogger logger) {
        switch (log.getKind()) {
            case ERROR:
                logger.error(log);
                break;
            case MANDATORY_WARNING:
            case WARNING:
                logger.warning(log);
                break;
            case OTHER:
            case NOTE:
                logger.info(log);
                break;
        }
    }
}
