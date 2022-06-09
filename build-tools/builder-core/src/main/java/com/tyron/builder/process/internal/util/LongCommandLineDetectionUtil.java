package com.tyron.builder.process.internal.util;

import com.tyron.builder.internal.os.OperatingSystem;

import java.util.List;

public class LongCommandLineDetectionUtil {
    // See http://msdn.microsoft.com/en-us/library/windows/desktop/ms682425(v=vs.85).aspx
    public static final int MAX_COMMAND_LINE_LENGTH_WINDOWS = 32767;
    // Derived from default when running getconf ARG_MAX in OSX
    public static final int MAX_COMMAND_LINE_LENGTH_OSX = 262144;
    // Dervied from MAX_ARG_STRLEN as per http://man7.org/linux/man-pages/man2/execve.2.html
    public static final int MAX_COMMAND_LINE_LENGTH_NIX = 131072;
    private static final String WINDOWS_LONG_COMMAND_EXCEPTION_MESSAGE = "The filename or extension is too long";
    private static final String NIX_LONG_COMMAND_EXCEPTION_MESSAGE = "error=7, Argument list too long";

    public static boolean hasCommandLineExceedMaxLength(String command, List<String> arguments) {
        int commandLineLength = command.length() + arguments.stream().map(String::length).reduce(Integer::sum).orElse(0) + arguments.size();
        return commandLineLength > getMaxCommandLineLength();
    }

    private static int getMaxCommandLineLength() {
        int defaultMax = MAX_COMMAND_LINE_LENGTH_NIX;
        if (OperatingSystem.current().isMacOsX()) {
            defaultMax = MAX_COMMAND_LINE_LENGTH_OSX;
        } else if (OperatingSystem.current().isWindows()) {
            defaultMax = MAX_COMMAND_LINE_LENGTH_WINDOWS;
        }
        // in chars
        return Integer.getInteger("com.tyron.builder.internal.cmdline.max.length", defaultMax);
    }

    public static boolean hasCommandLineExceedMaxLengthException(Throwable failureCause) {
        Throwable cause = failureCause;
        do {
            if (cause.getMessage().contains(WINDOWS_LONG_COMMAND_EXCEPTION_MESSAGE) || cause.getMessage().contains(NIX_LONG_COMMAND_EXCEPTION_MESSAGE)) {
                return true;
            }
        } while ((cause = cause.getCause()) != null);

        return false;
    }
}
