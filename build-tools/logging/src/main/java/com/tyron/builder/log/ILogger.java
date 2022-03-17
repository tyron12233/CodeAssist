package com.tyron.builder.log;

import com.tyron.builder.model.DiagnosticWrapper;

import javax.tools.Diagnostic;

public interface ILogger {

    ILogger STD_OUT = new ILogger() {
        @Override
        public void info(DiagnosticWrapper wrapper) {
            System.out.println("[INFO] " + wrapper.toString());
        }

        @Override
        public void debug(DiagnosticWrapper wrapper) {
            System.out.println("[DEBUG] " + wrapper.toString());
        }

        @Override
        public void warning(DiagnosticWrapper wrapper) {
            System.out.println("[WARNING] " + wrapper.toString());
        }

        @Override
        public void error(DiagnosticWrapper wrapper) {
            System.out.println("[ERROR] " + wrapper.toString());
        }
    };

    ILogger EMPTY = new ILogger() {
        @Override
        public void info(DiagnosticWrapper wrapper) {

        }

        @Override
        public void debug(DiagnosticWrapper wrapper) {

        }

        @Override
        public void warning(DiagnosticWrapper wrapper) {

        }

        @Override
        public void error(DiagnosticWrapper wrapper) {

        }
    };

    static ILogger wrap(LogViewModel logViewModel) {
        return new ILogger() {
            @Override
            public void info(DiagnosticWrapper wrapper) {
                logViewModel.d(LogViewModel.BUILD_LOG, wrapper);
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
        DiagnosticWrapper wrapped = wrap(message);
        wrapped.setKind(Diagnostic.Kind.WARNING);
        warning(wrapped);
    }

    default void error(String message) {
       DiagnosticWrapper wrapped = wrap(message);
       wrapped.setKind(Diagnostic.Kind.ERROR);
       error(wrapped);
    }

    default void verbose(String message) {

    }

    static DiagnosticWrapper wrap(String message) {
        DiagnosticWrapper wrapper = new DiagnosticWrapper();
        wrapper.setMessage(message);
        return wrapper;
    }
}
