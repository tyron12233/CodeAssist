package com.tyron.builder.log;

import com.tyron.builder.model.DiagnosticWrapper;

public interface ILogger {

    public static ILogger wrap(LogViewModel logViewModel) {
        return new ILogger() {
            @Override
            public void info(DiagnosticWrapper wrapper) {

            }

            @Override
            public void debug(DiagnosticWrapper wrapper) {
                logViewModel.d(LogViewModel.BUILD_LOG, wrapper);
            }

            @Override
            public void warning(DiagnosticWrapper wrapper) {
                logViewModel.w(LogViewModel.BUILD_LOG, wrapper);
            }

            @Override
            public void error(DiagnosticWrapper wrapper) {
                logViewModel.e(LogViewModel.BUILD_LOG, wrapper);
            }
        };
    }
    void info(DiagnosticWrapper wrapper);

    void debug(DiagnosticWrapper wrapper);

    void warning(DiagnosticWrapper wrapper);

    void error (DiagnosticWrapper wrapper);



    default void info(String message) {
        debug(wrap(message));
    }

    default void debug(String message) {
        debug(wrap(message));
    }

    default void warning(String message) {
        warning(wrap(message));
    }

    default void error(String message) {
       error(wrap(message));
    }

    default void verbose(String message) {

    }

    static DiagnosticWrapper wrap(String message) {
        DiagnosticWrapper wrapper = new DiagnosticWrapper();
        wrapper.setMessage(message);
        return wrapper;
    }
}
