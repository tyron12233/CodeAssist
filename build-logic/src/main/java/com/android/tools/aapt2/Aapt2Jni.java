package com.android.tools.aapt2;

import com.tyron.builder.model.DiagnosticWrapper;

import org.openjdk.javax.tools.Diagnostic;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Aapt2Jni {

    private static final int LOG_LEVEL_ERROR = 3;
    private static final int LOG_LEVEL_WARNING = 2;
    private static final int LOG_LEVEL_INFO = 1;

    private static final Aapt2Jni INSTANCE = new Aapt2Jni();

    public static Aapt2Jni getInstance() {
        return INSTANCE;
    }

    private String mFailureString;

    private final List<DiagnosticWrapper> mDiagnostics = new ArrayList<>();

    private Aapt2Jni() {
        try {
            System.loadLibrary("aapt2_jni");
        } catch (Throwable e) {
            mFailureString = e.getMessage();
        }
    }

    /**
     * Called by AAPT2 through JNI.
     *
     * @param level log level (3 = error, 2 = warning, 1 = info)
     * @param path path to the file with the issue
     * @param line line number of the issue
     * @param message issue message
     */
    @SuppressWarnings({"unused", "SameParameterValue"})
    private void log(int level, String path, long line, String message) {
        DiagnosticWrapper wrapper = new DiagnosticWrapper();
        switch (level) {
            case LOG_LEVEL_ERROR:
                wrapper.setKind(Diagnostic.Kind.ERROR);
                break;
            case LOG_LEVEL_WARNING:
                wrapper.setKind(Diagnostic.Kind.WARNING);
                break;
            case LOG_LEVEL_INFO:
                wrapper.setKind(Diagnostic.Kind.NOTE);
                break;
            default:
                wrapper.setKind(Diagnostic.Kind.OTHER);
                break;
        }
        if (path != null) {
            wrapper.setSource(new File(path));
        }
        wrapper.setLineNumber(line);
        wrapper.setEndLine((int) line);
        wrapper.setStartLine((int) line);
        wrapper.setMessage(message);
        mDiagnostics.add(wrapper);
    }

    private void clearLogs() {
        mDiagnostics.clear();
    }

    /**
     * Compile resources with Aapt2
     * @param args the arguments to pass to aapt2
     * @return exit code, non zero if theres an error
     */
    public static int compile(List<String> args) {
        Aapt2Jni instance = Aapt2Jni.getInstance();
        instance.clearLogs();

        // aapt2 has failed to load, fail early
        if (instance.mFailureString != null) {
            instance.log(LOG_LEVEL_ERROR, null, -1, instance.mFailureString);
            return -1;
        }

        return nativeCompile(args, instance);
    }

    public static int link(List<String> args) {
        Aapt2Jni instance = Aapt2Jni.getInstance();
        instance.clearLogs();

        // aapt2 has failed to load, fail early
        if (instance.mFailureString != null) {
            instance.log(LOG_LEVEL_ERROR, null, -1, instance.mFailureString);
            return -1;
        }

        return nativeLink(args, instance);
    }

    public static List<DiagnosticWrapper> getLogs() {
        return getInstance().mDiagnostics;
    }

    private static native int nativeCompile(List<String> args, Aapt2Jni clazz);

    private static native int nativeLink(List<String> args, Aapt2Jni clazz);
}
