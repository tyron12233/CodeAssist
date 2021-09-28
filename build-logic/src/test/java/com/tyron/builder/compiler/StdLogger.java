package com.tyron.builder.compiler;

import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;

import java.util.Locale;

/**
 * Utility class for forwarding Logs with the compiler to System.out
 */
public class StdLogger implements ILogger {

    public static StdLogger INSTANCE = new StdLogger();

    @Override
    public void info(DiagnosticWrapper wrapper) {
        printDiagnostic(wrapper);
    }

    @Override
    public void debug(DiagnosticWrapper wrapper) {
        printDiagnostic(wrapper);
    }

    @Override
    public void warning(DiagnosticWrapper wrapper) {
        printDiagnostic(wrapper);
    }

    @Override
    public void error(DiagnosticWrapper wrapper) {
        printDiagnostic(wrapper);
    }

    private void printDiagnostic(DiagnosticWrapper wrapper) {
        String message = wrapper.getKind() + ": " + wrapper.getMessage(Locale.ENGLISH);
        System.out.println(message);
    }

}
