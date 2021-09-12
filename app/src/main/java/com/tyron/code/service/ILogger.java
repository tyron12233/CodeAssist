package com.tyron.code.service;

import androidx.annotation.Nullable;

import com.tyron.code.model.DiagnosticWrapper;

import java.util.Locale;

public interface ILogger {

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
