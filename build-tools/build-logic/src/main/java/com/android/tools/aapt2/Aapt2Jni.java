package com.android.tools.aapt2;

import androidx.annotation.VisibleForTesting;

import com.tyron.builder.BuildModule;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.common.util.BinaryExecutor;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;

public class Aapt2Jni {

    private static final Pattern DIAGNOSTIC_PATTERN = Pattern.compile("(.*?):(\\d+): (.*?): (.+)");
    private static final Pattern DIAGNOSTIC_PATTERN_NO_LINE = Pattern.compile("(.*?): (.*?)" + ":" +
            " (.+)");
    private static final Pattern ERROR_PATTERN_NO_LINE = Pattern.compile("(error:) (.*?)");

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

    }

    private static int getLineNumber(String number) {
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int getLogLevel(String level) {
        if (level == null) {
            return 1;
        }
        switch (level) {
            case "error":
                return 3;
            case "warning":
                return 2;
            default:
            case "info":
                return 1;
        }
    }

    /**
     * Called by AAPT2 through JNI.
     *
     * @param level   log level (3 = error, 2 = warning, 1 = info)
     * @param path    path to the file with the issue
     * @param line    line number of the issue
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
        if (line != -1) {
            wrapper.setLineNumber(line);
            wrapper.setEndLine((int) line);
            wrapper.setStartLine((int) line);
        }
        wrapper.setMessage(message);
        mDiagnostics.add(wrapper);
    }

    private void clearLogs() {
        mDiagnostics.clear();
    }

    /**
     * Compile resources with Aapt2
     *
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

        args.add(0, "compile");
        args.add(0, getBinary());
        return executeBinary(args, instance);
    }

    public static int link(List<String> args) {
        Aapt2Jni instance = Aapt2Jni.getInstance();
        instance.clearLogs();

        // aapt2 has failed to load, fail early
        if (instance.mFailureString != null) {
            instance.log(LOG_LEVEL_ERROR, null, -1, instance.mFailureString);
            return -1;
        }

        args.add(0, "link");
        args.add(0, getBinary());

        return executeBinary(args, instance);
    }

    private static File sAapt2Binary;

    @VisibleForTesting
    public static void setAapt2Binary(File file) {
        sAapt2Binary = file;
    }

    private static String getBinary() {
        if (sAapt2Binary != null) {
            return sAapt2Binary.getAbsolutePath();
        }
        return BuildModule.getContext().getApplicationInfo().nativeLibraryDir + "/libaapt2.so";
    }

    private static int executeBinary(List<String> args, Aapt2Jni logger) {
        BinaryExecutor binaryExecutor = new BinaryExecutor();
        binaryExecutor.setCommands(args);
        String execute = binaryExecutor.execute();
        String[] lines = execute.split("\n");
        for (String line : lines) {
            if (StringUtils.isEmpty(line)) {
                continue;
            }

            Matcher matcher = DIAGNOSTIC_PATTERN.matcher(line);
            Matcher m = DIAGNOSTIC_PATTERN_NO_LINE.matcher(line);
            Matcher error = ERROR_PATTERN_NO_LINE.matcher(line);

            String path;
            String lineNumber;
            String level;
            String message;
            if (matcher.find()) {
                path = matcher.group(1);
                lineNumber = matcher.group(2);
                level = matcher.group(3);
                message = matcher.group(4);

            } else if (m.find()) {
                path = m.group(1);
                lineNumber = "-1";
                level = m.group(2);
                message = m.group(3);
            } else {
                String trim = line.trim();
                if (trim.startsWith("error")) {
                    level = "error";
                } else {
                    level = "info";
                }
                path = "";
                lineNumber = "-1";
                message = line;
            }

            logger.log(getLogLevel(level), path, getLineNumber(lineNumber), message);
        }
        return logger.mDiagnostics.stream().anyMatch(it -> it.getKind() == Diagnostic.Kind.ERROR) ? 1 : 0;
    }

    public static List<DiagnosticWrapper> getLogs() {
        return getInstance().mDiagnostics;
    }
}
